package com.alan344.idautofill.mybatis;

import com.alan344.idautofill.IdFill;
import lombok.Getter;
import lombok.Setter;

import java.lang.reflect.Method;

/**
 * @author AlanSun
 * @date 2022/10/27 16:29
 */
@Getter
@Setter
public class IdFillMethod {
    public static final IdFillMethod NULL = new IdFillMethod();

    private String fieldName;

    private String columnName;

    private IdFill idFill;

    private Method setMethod;

    private Method getMethod;
}
