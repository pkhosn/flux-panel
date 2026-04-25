package com.admin.common.interceptor;


import com.admin.entity.User;
import com.admin.common.exception.UnauthorizedException;
import com.admin.common.utils.JwtUtil;
import com.admin.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;


/**
 * JWT拦截器，验证用户是否登录
 */
@Component
public class JwtInterceptor implements HandlerInterceptor {

    @Autowired
    private UserService userService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String token = request.getHeader("Authorization");

        if (!StringUtils.hasText(token)) {
            throw new UnauthorizedException("未登录或token已过期");
        }


        if (!JwtUtil.validateToken(token)) {
            throw new UnauthorizedException("无效的token或token已过期");
        }

        Long userId = JwtUtil.getUserIdFromToken(token);
        if (userId == null) {
            throw new UnauthorizedException("无法获取用户权限信息");
        }

        User user = userService.getById(userId);
        if (user == null) {
            throw new UnauthorizedException("用户不存在或已被删除");
        }

        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new UnauthorizedException("用户已被禁用");
        }

        return true;
    }
}
