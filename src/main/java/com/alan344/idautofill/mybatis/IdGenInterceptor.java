package com.alan344.idautofill.mybatis;

import com.alan344.idautofill.IdFill;
import com.alan344.idautofill.IdFillService;
import com.google.common.base.CaseFormat;
import org.apache.commons.lang3.StringUtils;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.*;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.ObjectTypeHandler;
import org.mybatis.dynamic.sql.insert.render.DefaultInsertStatementProvider;
import org.mybatis.dynamic.sql.insert.render.MultiRowInsertStatementProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.AnnotationUtils;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.*;

/**
 * @author AlanSun
 * @date 2022/10/27 14:01
 * <p>
 * id fill
 **/
@ConditionalOnProperty(prefix = "base.mapper", name = "enable-uid-gen")
@Intercepts({@Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class})})
public class IdGenInterceptor implements Interceptor {
    @Autowired
    private IdFillService idFillService;
    /**
     * 类和对应id属性的Set方法缓存
     */
    private final Map<Type, IdFillMethod> typeIdSetMethodMap = new HashMap<>(64);

    private final Map<String, MappedStatement> classMappedStatementMap = new HashMap<>(64);

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        Object[] args = invocation.getArgs();
        MappedStatement mappedStatement = (MappedStatement) args[0];
        SqlCommandType sqlCommandType = mappedStatement.getSqlCommandType();
        if (!SqlCommandType.INSERT.equals(sqlCommandType)) {
            return invocation.proceed();
        }
        this.fillId(args, this.getRecord(args[1]));
        return invocation.proceed();
    }

    /**
     * 生成分布式 id 并填充到 model
     */
    private void fillId(Object[] args, Object obj) throws InvocationTargetException, IllegalAccessException {
        Class<?> clazz = obj.getClass();
        // 是否是集合
        boolean isCollection = false;
        Collection<?> collection = null;
        if (MultiRowInsertStatementProvider.class.isAssignableFrom(clazz)) {
            final MultiRowInsertStatementProvider<?> multiRowInsertStatementProvider = (MultiRowInsertStatementProvider<?>) obj;
            final Object o = multiRowInsertStatementProvider.getRecords().get(0);
            clazz = o.getClass();
            isCollection = true;
            collection = multiRowInsertStatementProvider.getRecords();
        }

        // 有 {@link IdGen} 注解的第一个字段的 set 方法
        IdFillMethod idFillMethod = this.getIdFillMethod(clazz);

        if (IdFillMethod.NULL == idFillMethod) {
            return;
        }

        if (isCollection) {
            this.multiHandle(collection, idFillMethod);
        } else {
            this.singleHandle(args, obj, idFillMethod);
        }
    }

    private void singleHandle(Object[] args, Object obj, IdFillMethod idFillMethod) throws InvocationTargetException, IllegalAccessException {
        final Method getMethod = idFillMethod.getGetMethod();
        final Object result = getMethod.invoke(obj);
        if (result != null) {
            return;
        }
        final IdFill idFill = idFillMethod.getIdFill();
        final long id = idFillService.getId(idFill);
        idFillMethod.getSetMethod().invoke(obj, id);

        MappedStatement mappedStatement = (MappedStatement) args[0];
        final Object parameter = args[1];
        final BoundSql boundSql = mappedStatement.getBoundSql(parameter);
        // 处理调用 insertSelective 没有 id 字段的情况
        this.setFieldInToSqlIfNecessary(args, idFillMethod, boundSql.getSql());
    }

    private void multiHandle(Collection<?> collection, IdFillMethod idFillMethod) throws InvocationTargetException, IllegalAccessException {
        final IdFill idFill = idFillMethod.getIdFill();
        final Method getMethod = idFillMethod.getGetMethod();
        final Method setMethod = idFillMethod.getSetMethod();
        for (Object o : collection) {
            final Object result = getMethod.invoke(o);
            if (result != null) {
                break;
            }
            final long id = idFillService.getId(idFill);
            setMethod.invoke(o, id);
        }
    }

    /**
     * 处理 insertSelective 方法，没有 id 字段的情况
     */
    private void setFieldInToSqlIfNecessary(Object[] args, IdFillMethod idFillMethod, String oldSql) {
        MappedStatement ms = (MappedStatement) args[0];
        final BoundSql boundSql = ms.getBoundSql(args[1]);
        final String sql = boundSql.getSql();
        if (!this.needHandleSql(sql, idFillMethod.getColumnName())) {
            return;
        }

        MappedStatement mappedStatement = classMappedStatementMap.get(oldSql);
        if (mappedStatement != null) {
            args[0] = mappedStatement;
            return;
        }

        final StringBuilder stringBuilder = new StringBuilder(sql);
        int offset = stringBuilder.indexOf("(");
        stringBuilder.insert(offset + 1, idFillMethod.getFieldName() + ", ");

        offset = stringBuilder.lastIndexOf("(");
        stringBuilder.insert(offset + 1, "?, ");

        final BoundSql newBoundSql = new BoundSql(ms.getConfiguration(), stringBuilder.toString(), boundSql.getParameterMappings(), boundSql.getParameterObject());
        final BoundSqlSqlSource boundSqlSqlSource = new BoundSqlSqlSource(newBoundSql);

        final List<ParameterMapping> parameterMappings = newBoundSql.getParameterMappings();
        final ParameterMapping parameterMapping = parameterMappings.get(0);
        final String property = parameterMapping.getProperty();
        final int i = property.indexOf(".");
        final String indexName = property.substring(0, i + 1);

        final ParameterMapping.Builder builder = new ParameterMapping.Builder(ms.getConfiguration(), indexName + idFillMethod.getFieldName(), Object.class);
        final ParameterMapping build = builder.mode(ParameterMode.IN).jdbcType(JdbcType.BIGINT).typeHandler(new ObjectTypeHandler()).build();
        parameterMappings.add(0, build);

        mappedStatement = this.newMappedStatement(ms, boundSqlSqlSource);
        classMappedStatementMap.put(oldSql, mappedStatement);
        args[0] = mappedStatement;
    }

    /**
     * 是否需要对 sql 处理
     *
     * @param sql        sql 原始sql
     * @param columnName 字段名称
     * @return true: 需要处理 sql; false: 不需要
     */
    private boolean needHandleSql(String sql, String columnName) {
        final String remove = StringUtils.remove(sql, " ");
        return !StringUtils.containsAnyIgnoreCase(remove, "(" + columnName + ",", "," + columnName + ",", "," + columnName + ")");
    }

    /**
     * 生成新的 MappedStatement
     *
     * @param ms           原始的 MappedStatement
     * @param newSqlSource SqlSource
     * @return 新的 MappedStatement
     */
    private MappedStatement newMappedStatement(MappedStatement ms, SqlSource newSqlSource) {
        MappedStatement.Builder builder = new MappedStatement.Builder(ms.getConfiguration(), ms.getId(), newSqlSource, ms.getSqlCommandType());
        builder.resource(ms.getResource());
        builder.fetchSize(ms.getFetchSize());
        builder.statementType(ms.getStatementType());
        builder.keyGenerator(ms.getKeyGenerator());
        if (ms.getKeyProperties() != null && ms.getKeyProperties().length != 0) {
            StringBuilder keyProperties = new StringBuilder();
            for (String keyProperty : ms.getKeyProperties()) {
                keyProperties.append(keyProperty).append(",");
            }
            keyProperties.delete(keyProperties.length() - 1, keyProperties.length());
            builder.keyProperty(keyProperties.toString());
        }
        builder.timeout(ms.getTimeout());
        builder.parameterMap(ms.getParameterMap());
        builder.resultMaps(ms.getResultMaps());
        builder.resultSetType(ms.getResultSetType());
        builder.cache(ms.getCache());
        builder.flushCacheRequired(ms.isFlushCacheRequired());
        builder.useCache(ms.isUseCache());

        return builder.build();
    }

    /**
     * 获取 IdFillMethod
     *
     * @param clazz 参数
     * @return {@link IdFillMethod}
     */
    private IdFillMethod getIdFillMethod(Class<?> clazz) {
        // 有 {@link IdGen} 注解的第一个字段的 set 方法
        IdFillMethod methodWithIdGenAnnotation = typeIdSetMethodMap.get(clazz);
        if (null == methodWithIdGenAnnotation) {
            // 设置默认值，防止重复处理
            methodWithIdGenAnnotation = this.parse(clazz);
            if (null == methodWithIdGenAnnotation) {
                methodWithIdGenAnnotation = IdFillMethod.NULL;
            }
            typeIdSetMethodMap.put(clazz, methodWithIdGenAnnotation);
        }
        return methodWithIdGenAnnotation;
    }

    /**
     * 获取实际的插入记录
     *
     * @param obj obj
     * @return 用户传入的记录
     */
    private Object getRecord(Object obj) {
        if (obj.getClass().isAssignableFrom(DefaultInsertStatementProvider.class)) {
            return ((DefaultInsertStatementProvider<?>) obj).getRow();
        } else {
            return obj;
        }
    }

    /**
     * 获取有 {@link IdFill} 注解的第一个字段的 set 方法
     *
     * @param clazz 类 class
     * @return 有 {@link IdFill} 注解的第一个字段的 set 方法
     */
    private IdFillMethod parse(Class<?> clazz) {
        final Field[] declaredFields = clazz.getDeclaredFields();
        Field fieldWithIdGenAnnotation = null;
        IdFill annotation = null;
        for (Field field : declaredFields) {
            annotation = AnnotationUtils.getAnnotation(field, IdFill.class);
            if (null != annotation) {
                fieldWithIdGenAnnotation = field;
                break;
            }
        }
        if (null == annotation) {
            return null;
        }

        // 字段名称
        final String fieldName = fieldWithIdGenAnnotation.getName();

        // get method if 'set' concat field's name in lower case equal method's name in lower case
        final Method[] declaredMethods = clazz.getDeclaredMethods();
        final Optional<Method> setMethodOpt = Arrays.stream(declaredMethods)
                .filter(method -> !method.isBridge() && method.getName().toLowerCase().equals("set" + fieldName.toLowerCase()))
                .findFirst();

        final Optional<Method> getMethodOpt = Arrays.stream(declaredMethods)
                .filter(method -> !method.isBridge() && method.getName().toLowerCase().equals("get" + fieldName.toLowerCase()))
                .findFirst();

        // 是否有 set 方法，如果方法不存在则不处理
        if (!setMethodOpt.isPresent() || !getMethodOpt.isPresent()) {
            return null;
        }

        IdFillMethod idFillMethod = new IdFillMethod();
        idFillMethod.setFieldName(fieldName);
        // 获取字段名称
        String columnName = annotation.columnName();
        if (StringUtils.isEmpty(columnName) && annotation.columnNameFromProperty()) {
            columnName = CaseFormat.LOWER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, fieldName);
        }
        idFillMethod.setColumnName(columnName);
        idFillMethod.setIdFill(annotation);
        idFillMethod.setSetMethod(setMethodOpt.get());
        idFillMethod.setGetMethod(getMethodOpt.get());
        return idFillMethod;
    }

    /**
     * 定义一个内部辅助类，作用是包装sql
     */
    static class BoundSqlSqlSource implements SqlSource {
        private final BoundSql boundSql;

        public BoundSqlSqlSource(BoundSql boundSql) {
            this.boundSql = boundSql;
        }

        @Override
        public BoundSql getBoundSql(Object parameterObject) {
            return boundSql;
        }
    }
}