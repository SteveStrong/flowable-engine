/* Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.flowable.app.conf;

import org.flowable.app.idm.properties.FlowableIdmAppProperties;
import org.flowable.app.properties.FlowableRestAppProperties;
import org.flowable.app.security.AjaxAuthenticationFailureHandler;
import org.flowable.app.security.AjaxAuthenticationSuccessHandler;
import org.flowable.app.security.AjaxLogoutSuccessHandler;
import org.flowable.app.security.ClearFlowableCookieLogoutHandler;
import org.flowable.app.security.CustomDaoAuthenticationProvider;
import org.flowable.app.security.CustomLdapAuthenticationProvider;
import org.flowable.app.security.CustomPersistentRememberMeServices;
import org.flowable.app.security.DefaultPrivileges;
import org.flowable.app.security.Http401UnauthorizedEntryPoint;
import org.flowable.app.web.CustomFormLoginConfig;
import org.flowable.idm.api.IdmIdentityService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.info.InfoEndpoint;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.RememberMeServices;
import org.springframework.security.web.header.writers.XXssProtectionHeaderWriter;

/**
 * Based on http://docs.spring.io/spring-security/site/docs/3.2.x/reference/htmlsingle/#multiple-httpsecurity
 *
 * @author Joram Barrez
 * @author Tijs Rademakers
 * @author Filip Hrisafov
 */
@Configuration
@EnableGlobalMethodSecurity(prePostEnabled = true, jsr250Enabled = true)
public class SecurityConfiguration {

    //
    // GLOBAL CONFIG
    //

    @Autowired
    protected IdmIdentityService identityService;
    
    @Autowired
    protected FlowableIdmAppProperties idmAppProperties;
    
    @Bean
    public UserDetailsService userDetailsService() {
        org.flowable.app.security.UserDetailsService userDetailsService = new org.flowable.app.security.UserDetailsService();
        userDetailsService.setUserValidityPeriod(idmAppProperties.getSecurity().getUserValidityPeriod());
        return userDetailsService;
    }

    @Bean(name = "dbAuthenticationProvider")
    @ConditionalOnMissingBean(AuthenticationProvider.class)
    @ConditionalOnProperty(prefix = "flowable.idm.ldap", name = "enabled", havingValue = "false", matchIfMissing = true)
    public AuthenticationProvider dbAuthenticationProvider(PasswordEncoder passwordEncoder) {
        CustomDaoAuthenticationProvider daoAuthenticationProvider = new CustomDaoAuthenticationProvider();
        daoAuthenticationProvider.setUserDetailsService(userDetailsService());
        daoAuthenticationProvider.setPasswordEncoder(passwordEncoder);
        return daoAuthenticationProvider;
    }

    @Bean(name = "ldapAuthenticationProvider")
    @ConditionalOnProperty(prefix = "flowable.idm.ldap", name = "enabled", havingValue = "true")
    public AuthenticationProvider ldapAuthenticationProvider() {
        CustomLdapAuthenticationProvider ldapAuthenticationProvider = new CustomLdapAuthenticationProvider(
                userDetailsService(), identityService);
        return ldapAuthenticationProvider;
    }

    //
    // REGULAR WEBAP CONFIG
    //

    @Configuration
    @Order(10) // API config first (has Order(1))
    public static class FormLoginWebSecurityConfigurerAdapter extends WebSecurityConfigurerAdapter {

        @Autowired
        private FlowableIdmAppProperties idmAppProperties;

        @Autowired
        private AjaxAuthenticationSuccessHandler ajaxAuthenticationSuccessHandler;

        @Autowired
        private AjaxAuthenticationFailureHandler ajaxAuthenticationFailureHandler;

        @Autowired
        private AjaxLogoutSuccessHandler ajaxLogoutSuccessHandler;

        @Autowired
        private Http401UnauthorizedEntryPoint authenticationEntryPoint;

        @Autowired
        private RememberMeServices rememberMeServices;

        @Override
        protected void configure(HttpSecurity http) throws Exception {
            http
                    .exceptionHandling()
                    .authenticationEntryPoint(authenticationEntryPoint)
                    .and()
                    .sessionManagement()
                    .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                    .and()
                    .rememberMe()
                    .rememberMeServices(rememberMeServices)
                    .key(idmAppProperties.getSecurity().getRememberMeKey())
                    .and()
                    .logout()
                    .logoutUrl("/app/logout")
                    .logoutSuccessHandler(ajaxLogoutSuccessHandler)
                    .addLogoutHandler(new ClearFlowableCookieLogoutHandler())
                    .permitAll()
                    .and()
                    .csrf()
                    .disable() // Disabled, cause enabling it will cause sessions
                    .headers()
                    .frameOptions()
                    .sameOrigin()
                    .addHeaderWriter(new XXssProtectionHeaderWriter())
                    .and()
                    .authorizeRequests()
                    .antMatchers("/*").permitAll()
                    .antMatchers("/app/rest/authenticate").permitAll()
                    .antMatchers("/app/**").hasAuthority(DefaultPrivileges.ACCESS_IDM);

            // Custom login form configurer to allow for non-standard HTTP-methods (eg. LOCK)
            CustomFormLoginConfig<HttpSecurity> loginConfig = new CustomFormLoginConfig<>();
            loginConfig.loginProcessingUrl("/app/authentication")
                    .successHandler(ajaxAuthenticationSuccessHandler)
                    .failureHandler(ajaxAuthenticationFailureHandler)
                    .usernameParameter("j_username")
                    .passwordParameter("j_password")
                    .permitAll();

            http.apply(loginConfig);
        }

    }

    @Bean
    public CustomPersistentRememberMeServices rememberMeServices() {
        return new CustomPersistentRememberMeServices(idmAppProperties, userDetailsService());
    }

    //
    // BASIC AUTH
    //

    @Configuration
    @Order(1)
    public static class ApiWebSecurityConfigurationAdapter extends WebSecurityConfigurerAdapter {

        protected final FlowableRestAppProperties restAppProperties;
        protected final FlowableIdmAppProperties idmAppProperties;

        public ApiWebSecurityConfigurationAdapter(FlowableRestAppProperties restAppProperties,
            FlowableIdmAppProperties idmAppProperties) {
            this.restAppProperties = restAppProperties;
            this.idmAppProperties = idmAppProperties;
        }

        protected void configure(HttpSecurity http) throws Exception {

            http
                    .sessionManagement()
                    .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                    .and()
                    .csrf()
                    .disable();

            if (idmAppProperties.isRestEnabled()) {

                if (restAppProperties.isVerifyRestApiPrivilege()) {
                    http.antMatcher("/api/**").authorizeRequests().antMatchers("/api/**").hasAuthority(DefaultPrivileges.ACCESS_REST_API).and().httpBasic();
                } else {
                    http.antMatcher("/api/**").authorizeRequests().antMatchers("/api/**").authenticated().and().httpBasic();
                    
                }
                
            } else {
                http.antMatcher("/api/**").authorizeRequests().antMatchers("/api/**").denyAll();
                
            }
            
        }
    }

    //
    // Actuator
    //

    @ConditionalOnClass(EndpointRequest.class)
    @Configuration
    @Order(15) // Actuator configuration should kick in after the Form Login so the custom login filter can be applied before
    public static class ActuatorWebSecurityConfigurationAdapter extends WebSecurityConfigurerAdapter {

        @Override
        protected void configure(HttpSecurity http) throws Exception {

            http
                .sessionManagement()
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                .and()
                .csrf()
                .disable();

            http
                .authorizeRequests()
                .requestMatchers(EndpointRequest.to(InfoEndpoint.class, HealthEndpoint.class)).authenticated()
                .requestMatchers(EndpointRequest.toAnyEndpoint()).hasAnyAuthority(DefaultPrivileges.ACCESS_ADMIN)
                .and().httpBasic();
        }
    }
}
