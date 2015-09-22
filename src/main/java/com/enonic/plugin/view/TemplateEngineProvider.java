package com.enonic.plugin.view;

import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.spring3.templateresolver.SpringResourceTemplateResolver;

import javax.xml.ws.Provider;


@Component
public class TemplateEngineProvider implements Provider<TemplateEngine> {


    private TemplateEngine templateEngine;
    SpringResourceTemplateResolver templateResolver;


    public TemplateEngineProvider() throws Exception{
        templateResolver = new SpringResourceTemplateResolver();
        templateResolver.setTemplateMode("HTML5");
        templateResolver.setPrefix("/views/");
        templateResolver.setSuffix(".html");
        templateResolver.setCacheable(false);

        this.templateEngine = new TemplateEngine();
        this.templateEngine.setTemplateResolver(templateResolver);
    }
    public void setApplicationContext(ApplicationContext applicationContext) throws Exception{
        templateResolver.setApplicationContext(applicationContext);
        templateResolver.afterPropertiesSet();
    }


    public TemplateEngine get() {
        return this.templateEngine;
    }

    @Override
    public TemplateEngine invoke(TemplateEngine request) {
        return null;
    }
}