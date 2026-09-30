package com.neuroplan.auth.config;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class DeploymentSiteHeaderFilter extends OncePerRequestFilter {

    private final String deploymentSite;

    public DeploymentSiteHeaderFilter(
            @Value("${DEPLOYMENT_SITE:unknown}") String deploymentSite) {
        this.deploymentSite = deploymentSite;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        response.setHeader("X-Site", deploymentSite);
        filterChain.doFilter(request, response);
    }
}
