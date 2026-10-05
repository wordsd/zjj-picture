package com.zjj.zjjpicturebackend.annotation;

import java.lang.annotation.*;

/**
 * 权限校验注解，加在controller方法上
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AuthCheck {

    /**
     * 必须拥有的角色，默认普通用户
     * @return
     */
    String mustRole() default "";

}
