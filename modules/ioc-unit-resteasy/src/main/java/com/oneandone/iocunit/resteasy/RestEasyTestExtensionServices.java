package com.oneandone.iocunit.resteasy;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.spi.Extension;
import javax.ws.rs.Path;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.ext.Provider;

import org.jboss.resteasy.client.jaxrs.ResteasyClientBuilder;
import org.jboss.resteasy.spi.ResteasyProviderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.oneandone.cdi.weldstarter.CreationalContexts;
import com.oneandone.cdi.weldstarter.WeldSetupClass;
import com.oneandone.cdi.weldstarter.spi.TestExtensionService;
import com.oneandone.cdi.weldstarter.spi.WeldStarter;
import com.oneandone.iocunit.analyzer.ClasspathHandler;
import com.oneandone.iocunit.analyzer.ConfigStatics;
import com.oneandone.iocunit.resteasy.auth.AuthInterceptor;
import com.oneandone.iocunit.resteasy.auth.TestAuth;
import com.oneandone.iocunit.resteasy.servlet.IocUnitHttpServletRequest;
import com.oneandone.iocunit.resteasy.servlet.IocUnitHttpSession;
import com.oneandone.iocunit.util.Annotations;

/**
 * @author aschoerk
 */
public class RestEasyTestExtensionServices implements TestExtensionService {

    static ThreadLocal<Set<Class>> perAnnotationDefinedJaxRSClasses = new ThreadLocal<>();
    static ThreadLocal<Set<Class>> asCandidatesDefinedJaxRSClasses = new ThreadLocal<>();
    static ThreadLocal<Boolean> onlyAnnotationDefined = new ThreadLocal<>();

    public static ThreadLocal<TestAuth> testSecurityThreadLocal = new ThreadLocal<>();

    private static Logger logger = LoggerFactory.getLogger(RestEasyTestExtensionServices.class);

    @Override
    public void initAnalyze() {
        perAnnotationDefinedJaxRSClasses.set(new HashSet<>());
        asCandidatesDefinedJaxRSClasses.set(new HashSet<>());
        onlyAnnotationDefined.set(false);
    }

    @Override
    public List<Extension> getExtensions() {
        List<Extension> result = new ArrayList<>();
        try {
            if(Path.class.getName() != null) {
                result.add(new JaxRsRestEasyTestExtension());
            }
        } catch (NoClassDefFoundError ex) {
            ;
        }

        return result;
    }


    @Override
    public List<Class<?>> testClasses() {
        List<Class<?>> result = new ArrayList<>();
        result.add(RestEasyMockInit.class);
        result.add(AuthInterceptor.class);
        result.add(IocUnitResteasyHttpClient.class);
        try {
            Class<?> tmp = Class.forName("javax.servlet.http.HttpSession.class");
            result.add(IocUnitHttpSession.class);
            result.add(IocUnitHttpServletRequest.class);
        } catch(Exception e) {
            logger.info("Resteasy usage without HttpSession-Class.");
        }
        return result;
    }


    @Override
    public List<Class<?>> testAvailableClasses() {
        List<Class<?>> result = new ArrayList<>();
        try {
            Method[] m = ResteasyClientBuilder.class.getMethods();
            result.add(IocUnitResteasyClientBuilder.class);
            result.add(IocUnitResteasyWebTargetBuilder.class);
        } catch (NoClassDefFoundError e) {
            ; // no resteasy client module available
        }
        return result;
    }


    @Override
    public List<Class<? extends Annotation>> extraClassAnnotations() {
        return Arrays.asList(JaxRSClasses.class, JaxRSPackagesDeep.class);
    }

    @Override
    public List<Class<?>> handleExtraClassAnnotation(final Annotation annotation, Class<?> c) {
        List<Class<?>> res = new ArrayList<>();
        if(annotation.annotationType().equals(JaxRSClasses.class)) {
            final JaxRSClasses jaxrsAnnotation = (JaxRSClasses) annotation;
            Class<?>[] jaxRSClassesForThis = jaxrsAnnotation.value();
            if(jaxrsAnnotation.onlyDefinedByAnnotation()) {
                onlyAnnotationDefined.set(true);
            }
            if(jaxRSClassesForThis != null) {
                for (Class<?> clazz : jaxRSClassesForThis) {
                    perAnnotationDefinedJaxRSClasses.get().add(clazz);
                    res.add(clazz);
                }
            }
        }
        if(annotation.annotationType().equals(JaxRSPackagesDeep.class)) {
            final JaxRSPackagesDeep jaxrsAnnotation = (JaxRSPackagesDeep) annotation;
            Class<?>[] jaxRSClassesForThis = jaxrsAnnotation.value();
            if(jaxrsAnnotation.onlyDefinedByAnnotation()) {
                onlyAnnotationDefined.set(true);
            }
            if(jaxRSClassesForThis != null) {
                for (Class<?> clazz : jaxRSClassesForThis) {
                    Set<Class<?>> classes = new HashSet<>();
                    ClasspathHandler.addPackageDeep(clazz, classes, jaxrsAnnotation.filteringRegex());
                    for (Class<?> found : classes) {
                        if (found.getCanonicalName() != null && ConfigStatics.mightBeBean(found)) {
                            perAnnotationDefinedJaxRSClasses.get().add(found);
                            res.add(found);
                        }
                    }
                }
            }
        }
        return res;
    }

    @Override
    public void postStartupAction(final CreationalContexts creationalContexts, final WeldStarter weldStarter) {
        creationalContexts.create(RestEasyMockInit.class, ApplicationScoped.class);
        ResteasyProviderFactory.setInstance(null);
    }

    /**
     * Available classes can be evaluated to be forced to be started. The evaluation also can show that some of those classes might be strong
     * candidates to be started.
     *
     * @param c the class
     * @return true if candidate is voted to be started.
     */
    @Override
    public boolean candidateToStart(final Class<?> c) {
        if(c.isInstance(SecurityContext.class)) {
            logger.trace("Found SecurityContext in class: {}", c.getName());
        }
        if(c.isAnnotationPresent(Provider.class) || JaxRsRestEasyTestExtension.annotationPresent(c, Path.class)) {
            asCandidatesDefinedJaxRSClasses.get().add(c);
            if (onlyAnnotationDefined.get())
                return true;
        }
        if(perAnnotationDefinedJaxRSClasses.get().contains(c)) {
            return true;
        }
        else {
            return false;
        }
    }

    @Override
    public void preStartupAction(WeldSetupClass weldSetup, Class clazz, Method method) {
        for (Class<?> c : perAnnotationDefinedJaxRSClasses.get()) {
            if(!weldSetup.getBeanClasses().contains(c.getName())) {
                logger.info("Restresource or ExceptionMapper candidate: {} found "
                            + " added to testconfiguration.", c.getSimpleName());
                weldSetup.getBeanClasses().add(c.getName());
            }
        }
        for (Class<?> c : asCandidatesDefinedJaxRSClasses.get()) {
            if(!weldSetup.getBeanClasses().contains(c.getName())) {
                logger.warn("Restresource or ExceptionMapper candidate: {} found "
                            + " while scanning availables, but not in testconfiguration included.", c.getSimpleName());
            }
        }
        asCandidatesDefinedJaxRSClasses.get().clear(); // show only once
        TestAuth testAuth = Annotations.findAnnotation(clazz, method, TestAuth.class);
        if(testAuth != null) {
            testSecurityThreadLocal.set(testAuth);
            weldSetup.getBeanClasses().add(IocUnitSecurityContext.class.getName());
        }
        else {
            testSecurityThreadLocal.set(null);
        }
    }
}
