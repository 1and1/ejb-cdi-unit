package com.oneandone.ejbcdiunit.closure;

import javax.decorator.Decorator;
import javax.enterprise.inject.spi.Extension;
import javax.inject.Inject;
import javax.interceptor.Interceptor;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.net.MalformedURLException;
import java.util.*;

public class CdiConfigBuilder {

    private Builder builder;
    public static boolean mightBeBean(Class<?> c) {
        if (c.isInterface() || c.isPrimitive() || c.isLocalClass()
                || c.isAnonymousClass() || c.isLocalClass()
                || (c.getEnclosingClass() != null && !Modifier.isStatic(c.getModifiers())))
            return false;
        final Constructor<?>[] declaredConstructors = c.getDeclaredConstructors();
        if (declaredConstructors.length == 0)
            return false;
        boolean constructorOk = false;
        for (Constructor constructor : declaredConstructors) {
            if (constructor.getParameters().length == 0) {
                constructorOk = true;
            } else {
                if (constructor.getAnnotation(Inject.class) != null)
                    constructorOk = true;
            }
        }
        if (!constructorOk)
            return false;
        if (c.getAnnotation(Interceptor.class) != null && c.getAnnotation(Decorator.class) != null)
            return false;
        if (isExtension(c))
            return false;
        return true;
    }

    public static boolean isExtension(final Class<?> c) {
        return (Extension.class.isAssignableFrom(c));
    }

    public Collection<Class<? extends Extension>> getExtensions() {
        return builder.extensions;
    }

    public static class ProblemRecord {
        private final String msg;
        Collection<Class<?>>[] classes;
        QualifiedType inject;

        public ProblemRecord(String msg, QualifiedType inject, Collection<Class<?>>... classes) {
            this.classes = classes;
            this.inject = inject;
            this.msg = msg;
        }
    }

    List<ProblemRecord> problems = new ArrayList<>();


    void evaluateLevel(Set<Class<?>> beansToBeEvaluated) throws MalformedURLException {
        Set<Class<?>> currentToBeEvaluated = new HashSet<>(beansToBeEvaluated);
        this.builder = new Builder();
        // handle initial classes as testclasses
        for (Class<?> c : currentToBeEvaluated) {
            builder.testClass(c);
        }

        while (currentToBeEvaluated.size() > 0) {
            for (Class<?> c : currentToBeEvaluated) {
                if (mightBeBean(c)) {
                    builder.tobeStarted(c)
                            .setAvailable(c)
                            .innerClasses(c)
                            .injects(c)
                            .producerFields(c)
                            .producerMethods(c)
                            .testClassAnnotation(c)
                            .sutClassAnnotation(c)
                            .sutClasspathsAnnotation(c)
                            .sutPackagesAnnotation(c)
                            .alternatives(c);

                } else {
                    builder.elseClass(c);
                }
            }
            builder.moveToBeEvaluatedTo(currentToBeEvaluated);
        }


        while (true) {
            InjectsMatcher injectsMatcher = new InjectsMatcher(builder);
            injectsMatcher.match();

            Set<Class<?>> newToBeStarted = injectsMatcher.evaluateMatches(problems);

            if (newToBeStarted.size() > 0) {
                for (Class<?> c : newToBeStarted) {
                    builder.tobeStarted(c)
                            .setAvailable(c)
                            .innerClasses(c)
                            .injects(c)
                            .producerFields(c)
                            .producerMethods(c)
                            .testClassAnnotation(c)
                            .sutClassAnnotation(c)
                            .sutClasspathsAnnotation(c)
                            .sutPackagesAnnotation(c)
                            .alternatives(c);
                }
            } else {
                if (builder.injections.size() > 0) {
                    // search for producers in available classes
                    throw new RuntimeException("Not implemented yet");
                } else
                    return;
            }
        }

    }

    public Set<Class<?>> toBeStarted() {
        return this.builder.beansToBeStarted;
    }


    public void initialize(InitialConfiguration cfg) throws MalformedURLException {

        Set<Class<?>> tmp = new HashSet<>();
        if (cfg.testClass != null)
            tmp.add(cfg.testClass);
        tmp.addAll(cfg.initialClasses);
        evaluateLevel(tmp);

    }
}