package com.alan344.idautofill;

import com.alan344.uid.baidu.UidGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/**
 * @author AlanSun
 * @date 2022/12/10 16:50
 */
public class IdFillService {
    @Autowired
    private ApplicationContext applicationContext;

    public long getId(IdFill idFill) {
        final IdGenTypeEnum idGenTypeEnum = idFill.idGenType();
        if (idGenTypeEnum == IdGenTypeEnum.UID) {
            final UidGenerator uidGenerator = (UidGenerator) applicationContext.getBean(idFill.impl());
            return uidGenerator.getUID();
        }
        throw new RuntimeException("generate id fail");
    }
}
