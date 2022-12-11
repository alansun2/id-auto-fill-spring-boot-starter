package com.alan344.idautofill;

import com.alan344.uid.baidu.impl.CachedUidGenerator;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * @author AlanSun
 * @date 2022/10/27 14:04
 * <p>
 * id 生成器
 **/
@Target({FIELD})
@Retention(RUNTIME)
public @interface IdFill {
    /**
     * id 生成策略
     *
     * @return id 生成策略
     */
    IdGenTypeEnum idGenType() default IdGenTypeEnum.UID;

    /**
     * 具体的实现类，通过该名称获取 spring bean
     *
     * @return 实现类名称
     */
    Class<?> impl() default CachedUidGenerator.class;

    /**
     * 对应的字段名称
     *
     * @return 字段名称
     */
    String columnName() default "";

    /**
     * 如果 columnName 为空, 此值为 true 则把属性名从驼峰格式改为下划线格式
     *
     * @return true: 把属性值从驼峰转为下划线格式
     */
    boolean columnNameFromProperty() default true;
}