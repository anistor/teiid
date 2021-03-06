/*
 * JBoss, Home of Professional Open Source.
 * See the COPYRIGHT.txt file distributed with this work for information
 * regarding copyright ownership.  Some portions may be licensed
 * to Red Hat, Inc. under one or more contributor license agreements.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 * 02110-1301 USA.
 */
package org.teiid.olingo.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.apache.olingo.commons.api.Constants;
import org.apache.olingo.commons.api.edm.EdmOperation;
import org.apache.olingo.commons.api.edm.EdmParameter;
import org.apache.olingo.commons.api.edm.EdmReturnType;
import org.apache.olingo.commons.api.edm.EdmType;
import org.apache.olingo.commons.core.edm.primitivetype.SingletonPrimitiveType;
import org.apache.olingo.server.api.uri.UriParameter;
import org.apache.olingo.server.core.requests.ActionRequest;
import org.apache.olingo.server.core.requests.FunctionRequest;
import org.apache.olingo.server.core.requests.OperationRequest;
import org.teiid.core.TeiidException;
import org.teiid.core.types.BlobImpl;
import org.teiid.core.types.BlobType;
import org.teiid.core.types.ClobImpl;
import org.teiid.core.types.ClobType;
import org.teiid.core.types.DataTypeManager;
import org.teiid.core.types.InputStreamFactory;
import org.teiid.core.types.JDBCSQLTypeInfo;
import org.teiid.core.types.SQLXMLImpl;
import org.teiid.core.types.XMLType;
import org.teiid.core.util.ObjectConverterUtil;
import org.teiid.metadata.Column;
import org.teiid.metadata.ColumnSet;
import org.teiid.metadata.Procedure;
import org.teiid.metadata.ProcedureParameter;
import org.teiid.metadata.Schema;
import org.teiid.olingo.LiteralParser;
import org.teiid.olingo.api.ODataTypeManager;
import org.teiid.olingo.api.ProcedureReturnType;
import org.teiid.olingo.api.SQLParameter;
import org.teiid.query.sql.visitor.SQLStringVisitor;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class ProcedureSQLBuilder {
    private List<SQLParameter> sqlParameters = new ArrayList<SQLParameter>();
    private Schema teiidSchema;
    private Procedure procedure;
    private ProcedureReturn procedureReturn;
    private ParameterValueProvider parameterValueProvider;
    
    static class ProcedureReturn implements ProcedureReturnType {
        private EdmType type;
        private Integer sqlType = null;
        private boolean resultSetBasedLob;
        private boolean hasResultSet;
        
        public ProcedureReturn(EdmType type, Integer sqlType, boolean hasResultSet, boolean resultsetLob) {
            this.type = type;
            this.sqlType = sqlType;
            this.resultSetBasedLob = resultsetLob;
            this.hasResultSet = hasResultSet;
        }
        
        public EdmType getReturnType() {
            return type;
        }
        
        public boolean hasResultSet() {
            return this.hasResultSet;
        } 
        
        public boolean hasResultSetBasedLob() {
            return resultSetBasedLob;
        }
        
        public Integer getSqlType() {
            return sqlType;
        }        
    }
    
    interface ParameterValueProvider {
        Object getValue(EdmParameter parameter, Class<?> runtimeType) throws TeiidException;
    }    
    
    public ProcedureSQLBuilder(Schema schema, OperationRequest request) throws TeiidException {
        this.teiidSchema = schema;
        
        if (request instanceof FunctionRequest) {
            FunctionRequest functionRequest = (FunctionRequest)request;
            this.parameterValueProvider = new FunctionParameterValueProvider(functionRequest.getParameters());
            visit(functionRequest.getFunction());
        }
        else {
            ActionRequest actionRequest = (ActionRequest)request;
            this.parameterValueProvider = new ActionParameterValueProvider(actionRequest.getPayload());
            visit(actionRequest.getAction());
        }        
    }
    
    public ProcedureReturn getReturn() {
        return this.procedureReturn;
    }
    
    public void visit(EdmOperation edmOperation) throws TeiidException {
        this.procedure =  this.teiidSchema.getProcedure(edmOperation.getName());
        
        if (edmOperation.getReturnType() != null) {
            visit(edmOperation.getReturnType());
        }
        
        for (String parameterName : edmOperation.getParameterNames()) {
            visit(edmOperation.getParameter(parameterName));
        }
    }
    
    private boolean hasResultSet() {
        return this.procedure.getResultSet() != null;
    }
    
    private void visit(EdmReturnType returnType) {        
        EdmType type = returnType.getType();
        Class<?> teiidType = null;
        
        if (hasResultSet()) {
            Column column = getResultSetLobColumn();
            if (column != null) {
                // this special case where single LOB column in result treated as return
                teiidType = DataTypeManager.getDataTypeClass(column.getRuntimeType());
                Integer sqlType = JDBCSQLTypeInfo.getSQLType(DataTypeManager.getDataTypeName(teiidType));
                this.procedureReturn = new ProcedureReturn(type, sqlType, true, true);                
            }
            else {
                // this is complex type
                this.procedureReturn = new ProcedureReturn(type, null, true, false);
            }
        }
        else {
            // must not be null
            ProcedureParameter parameter = getReturnParameter();
            teiidType = DataTypeManager.getDataTypeClass(parameter.getRuntimeType());
            Integer sqlType = JDBCSQLTypeInfo.getSQLType(DataTypeManager.getDataTypeName(teiidType));
            this.procedureReturn = new ProcedureReturn(type, sqlType, false, false);
        }
    }
    
    private ProcedureParameter getReturnParameter() {
        for (ProcedureParameter parameter: this.procedure.getParameters()) {
            if (parameter.getType().equals(ProcedureParameter.Type.ReturnValue)) {
                return parameter;
            }
        }
        return null;
    }
    
    private Column getResultSetLobColumn() {
        ColumnSet<Procedure> returnColumns = this.procedure.getResultSet();
        if (returnColumns != null) {
            List<Column> columns = returnColumns.getColumns();
            if (columns.size() == 1 && DataTypeManager.isLOB(columns.get(0).getJavaType())) {
                return columns.get(0);
            }
        }
        return null;
    }

    private void visit(EdmParameter parameter) throws TeiidException {
        Class<?> runtimeType = resolveParameterType(this.procedure.getName(), parameter.getName());
        Integer sqlType = JDBCSQLTypeInfo.getSQLType(DataTypeManager.getDataTypeName(runtimeType));
        Object value = this.parameterValueProvider.getValue(parameter, runtimeType);
        this.sqlParameters.add(new SQLParameter(parameter.getName(), 
                ODataTypeManager.convertToTeiidRuntimeType(runtimeType, value), sqlType));
    }
    
    public String buildProcedureSQL() {
                
        StringBuilder sql = new StringBuilder();
        
        // fully qualify the procedure name
        if (getReturn().hasResultSet()) {
            sql.append("{"); //$NON-NLS-1$
        }
        else {
            sql.append("{? = "); //$NON-NLS-1$
        }
        
        sql.append("call ").append(SQLStringVisitor.escapeSinglePart(this.procedure.getFullName())); //$NON-NLS-1$ //$NON-NLS-2$
        sql.append("("); //$NON-NLS-1$
        
        boolean first = true;
        for (SQLParameter parameter:this.sqlParameters) {
            if (!first) {
                sql.append(","); //$NON-NLS-1$
            }
            first = false;
            sql.append(SQLStringVisitor.escapeSinglePart(parameter.getName())).append("=>?"); //$NON-NLS-1$            
                        
        }
        sql.append(")"); //$NON-NLS-1$
        sql.append("}"); //$NON-NLS-1$
        return sql.toString();
    }
    
    public Class<?> resolveParameterType(String procedureName, String parameterName) {
        for (ProcedureParameter pp : this.procedure.getParameters()) {
            if (pp.getName().equalsIgnoreCase(parameterName)) {
                return DataTypeManager.getDataTypeClass(pp.getRuntimeType());
            }
        }
        return null;
    }

    public List<SQLParameter> getSqlParameters() {
        return this.sqlParameters;
    }
    
    static class ActionParameterValueProvider implements ParameterValueProvider {
        private InputStream payload;
        private boolean alreadyConsumed;
        private byte[] jsonPayload;
        
        public ActionParameterValueProvider(InputStream payload) {
            this.payload = payload;
        }

        @Override
        public Object getValue(EdmParameter edmParameter, Class<?> runtimeType) throws TeiidException {
            if (!this.alreadyConsumed) {
                this.alreadyConsumed = true;
                if (DataTypeManager.isLOB(runtimeType)) {
                    InputStreamFactory isf = new InputStreamFactory() {
                        @Override
                        public InputStream getInputStream() throws IOException {
                            return payload;
                        }
                    };
                    if (runtimeType.isAssignableFrom(XMLType.class)) {
                        return new SQLXMLImpl(isf);    
                    } else if (runtimeType.isAssignableFrom(ClobType.class)) {
                        return new ClobImpl(isf, -1);
                    } else if (runtimeType.isAssignableFrom(BlobType.class)) {
                        return new BlobImpl(isf);
                    }
                } else {
                    this.jsonPayload = convertToByteArray(this.payload);
                }
            }
            List<?> values = parseParameter(new ByteArrayInputStream((byte[]) this.jsonPayload), 
                    edmParameter, runtimeType);
            if (edmParameter.isCollection()) {
                return values;
            } else {
                return values.get(0);
            }
        }
        
        private List<?> parseParameter(InputStream stream, EdmParameter parameter, 
                Class<?> runtimeType) throws TeiidException {
            try {
                ArrayList<Object> parsedValues = new ArrayList<Object>();
                ObjectMapper objectMapper = new ObjectMapper();
                objectMapper.configure(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY, true);
                JsonParser parser = new JsonFactory(objectMapper).createParser(stream);
                final ObjectNode tree = parser.getCodec().readTree(parser);
                JsonNode jsonNode = tree.get(Constants.VALUE);
                if (jsonNode != null) {
                    if (jsonNode.isArray()) {
                        ArrayNode arrayNode = (ArrayNode) jsonNode;
                        Iterator<JsonNode> it = arrayNode.iterator();
                        while (it.hasNext()) {
                            String value = it.next().get(parameter.getName()).asText();
                            parsedValues.add(LiteralParser.parseLiteral(parameter, runtimeType, value));
                        }
                    } else {
                        String value = jsonNode.asText();
                        parsedValues.add(LiteralParser.parseLiteral(parameter, runtimeType, value));
                    }
                    tree.remove(Constants.VALUE);
                    // if this is value there can be only one property
                    return parsedValues;
                }
                String value = tree.get(parameter.getName()).asText();
                parsedValues.add(LiteralParser.parseLiteral(parameter, runtimeType, value));
                return parsedValues;
            } catch (JsonParseException e) {
                throw new TeiidException(e);
            } catch (JsonMappingException e) {
                throw new TeiidException(e);
            } catch (IOException e) {
                throw new TeiidException(e);
            }
        }
        
        private byte[] convertToByteArray(InputStream payload) throws TeiidException {
            try {
                return ObjectConverterUtil.convertToByteArray(payload);
            } catch (IOException e) {
                throw new TeiidException(e);
            }
        }
    }  
    
    static class FunctionParameterValueProvider implements ParameterValueProvider {
        private List<UriParameter> parameters;
        
        public FunctionParameterValueProvider(List<UriParameter> parameters) {
            this.parameters = parameters;
        }
        
        @Override
        public Object getValue(EdmParameter edmParameter, Class<?> runtimeType) throws TeiidException {                
            for (UriParameter parameter : this.parameters) {
                if (parameter.getName().equals(edmParameter.getName())) {
                    // In Teiid one can only pass simple literal values, not complex
                    // types, no complex parsing required. And LOBs can not be inlined 
                    // for Function
                    return LiteralParser.parseLiteral(edmParameter, runtimeType, parameter.getText());
                }
            }
            return null;
        }
    }
}
