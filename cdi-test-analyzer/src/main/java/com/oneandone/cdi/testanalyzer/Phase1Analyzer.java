package com.oneandone.cdi.testanalyzer;

import com.oneandone.cdi.testanalyzer.annotations.*;
import com.oneandone.cdi.weldstarter.spi.TestExtensionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.inject.Produces;
import javax.enterprise.inject.Stereotype;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static com.oneandone.cdi.testanalyzer.ConfigStatics.doInClassAndSuperClasses;
import static com.oneandone.cdi.testanalyzer.ConfigStatics.isInterceptingBean;

/**
 * Analyzes the candidates. Works until no new candidates are found.
 * Finds injects. Detects producers.
 * Also detects interceptors, decorators, extension, stereotypes
 * Producers in classes to start found in producerMap. Producers in available classes found in availableProducerMap.
 */
class Phase1Analyzer {
    static Logger logger = LoggerFactory.getLogger(Phase1Analyzer.class);
    private final Configuration configuration;
    private ArrayList<Class<?>> newAvailables = new ArrayList<>();
    private Set<Class<?>> handledCandidates = new HashSet<>();

    public Phase1Analyzer(Configuration configuration) {
        this.configuration = configuration;
    }

    private void findInnerClasses(final Class c) {
        for (Class innerClass : c.getDeclaredClasses()) {
            if (Modifier.isStatic(innerClass.getModifiers()) && ConfigStatics.mightBeBean(innerClass)) {
                configuration.available(innerClass);
                if (configuration.isTestClass(c)) {
                    configuration.testClass(innerClass);
                } else {
                    configuration.sutClass(innerClass);
                }
                findInnerClasses(innerClass);
                newAvailables.add(innerClass);
            }
        }
    }


    private void innerClasses(Class c) {
        doInClassAndSuperClasses(c, c1 -> findInnerClasses(c1));
    }


    private void injects(Class c) {
        doInClassAndSuperClasses(c, c1 -> {
            InjectFinder injectFinder = new InjectFinder(configuration.testerExtensionsConfigsFinder);
            injectFinder.find(c1);
            for (QualifiedType i : injectFinder.getInjectedTypes())
                configuration.inject(i);
        });
    }

    private void testClassAnnotation(Class<?> c) {
        doInClassAndSuperClasses(c, c1 -> {
            TestClasses testClassesL = c1.getAnnotation(TestClasses.class);
            if (testClassesL != null) {
                for (Class<?> testClass : testClassesL.value()) {
                    configuration
                            .testClass(testClass)
                            .candidate(testClass);
                }
            }
        });
    }

    private void sutClassAnnotation(Class<?> c) {
        doInClassAndSuperClasses(c, c1 -> {
            SutClasses sutClassesL = c1.getAnnotation(SutClasses.class);
            if (sutClassesL != null) {
                for (Class<?> sutClass : sutClassesL.value()) {
                        configuration
                                .sutClass(sutClass)
                                .candidate(sutClass);
                }
            }
        });
    }


    void extraAnnotations(Class c) {
        final Map<Class<? extends Annotation>, TestExtensionService> extraClassAnnotations =
                configuration.testerExtensionsConfigsFinder.extraClassAnnotations;
        if (extraClassAnnotations.keySet().size() > 0) {
            doInClassAndSuperClasses(c, c1 -> {
                extraClassAnnotations.keySet()
                        .stream()
                        .map(a -> c1.getAnnotation((Class<? extends Annotation>) (a)))
                        .filter(res -> res != null)
                        .forEach(res -> extraClassAnnotations.get(((Annotation) res).annotationType())
                                .handleExtraClassAnnotation(res, c1));
            });
        }
    }

    private void addPackages(Class<?>[] packages, boolean isSut) throws MalformedURLException {
        for (Class<?> packageClass : packages) {
            Set<Class<?>> tmpClasses = new HashSet<>();
            ClasspathHandler.addPackage(packageClass, tmpClasses);
            addAvailables(isSut, tmpClasses);
        }
    }

    private void addClasspaths(Class<?>[] classpaths, boolean isSut) throws MalformedURLException {
        for (Class<?> classpathClass : classpaths) {
            Set<Class<?>> tmpClasses = new HashSet<>();
            ClasspathHandler.addClassPath(classpathClass, tmpClasses);
            addAvailables(isSut, tmpClasses);
        }
    }

    private void addAvailables(final boolean isSut, final Set<Class<?>> tmpClasses) {
        for (Class<?> c : tmpClasses) {
            if (c.isInterface() || c.isAnnotation() || Modifier.isAbstract(c.getModifiers()))
                continue;
            if (!isSut) {
                configuration.testClass(c);
            } else {
                configuration.sutClass(c);
            }
            configuration.available(c);
            newAvailables.add(c);
        }
    }

    private void packagesAnnotations(Class<?> c) {
        doInClassAndSuperClasses(c, c1 -> {
            try {
                SutPackages sutPackages = c1.getAnnotation(SutPackages.class);
                if (sutPackages != null) {
                    addPackages(sutPackages.value(), true);
                }
                TestPackages testPackages = c1.getAnnotation(TestPackages.class);
                if (testPackages != null) {
                    addPackages(testPackages.value(), false);
                }
            } catch (MalformedURLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private void classpathsAnnotations(Class<?> c) {
        doInClassAndSuperClasses(c, c1 -> {
            try {
                SutClasspaths sutClasspaths = c1.getAnnotation(SutClasspaths.class);
                if (sutClasspaths != null) {
                    addClasspaths(sutClasspaths.value(), true);
                }
                TestClasspaths testClasspaths = c1.getAnnotation(TestClasspaths.class);
                if (testClasspaths != null) {
                    addClasspaths(testClasspaths.value(), false);
                }
            } catch (MalformedURLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private void enabledAlternatives(Class<?> c) {
        doInClassAndSuperClasses(c, c1 -> {
            EnabledAlternatives enabledAlternativesL = c1.getAnnotation(EnabledAlternatives.class);
            if (enabledAlternativesL != null) {
                for (Class<?> aClass : enabledAlternativesL.value()) {
                    configuration
                            .testClass(aClass)
                            .candidate(aClass)
                            .enabledAlternative(aClass);
                }
            }
        });
    }

    private void excludes(Class<?> c) {
        doInClassAndSuperClasses(c, c1 -> {
            ExcludedClasses excludedClassesL = c1.getAnnotation(ExcludedClasses.class);
            if (excludedClassesL != null) {
                for (Class<?> aClass : excludedClassesL.value())
                    configuration.excluded(aClass);
            }
        });
    }

    private void customAnnotations(Class<?> c) {
        doInClassAndSuperClasses(c, c1 -> {
            Annotation[] annotations = c1.getAnnotations();
            for (Annotation ann : annotations) {
                final Class<? extends Annotation> annotationType = ann.annotationType();
                for (Annotation annann : annotationType.getAnnotations()) {
                    if (annann.annotationType().getPackage().equals(TestClasses.class.getPackage())) {
                        if (!configuration.isAvailable(annotationType)) {
                            testClassAnnotation(annotationType);
                            classpathsAnnotations(annotationType);
                            sutClassAnnotation(annotationType);
                            packagesAnnotations(annotationType);
                            enabledAlternatives(annotationType);
                            customAnnotations(annotationType);
                            extraAnnotations(annotationType);
                        }
                    }
                }
            }
        });
    }

    private boolean containsProducingAnnotation(final Annotation[] annotations) {
        for (Annotation ann : annotations) {
            if (ann.annotationType().equals(Produces.class)) {
                return true;
            }
        }
        return false;
    }

    private boolean isStereotype(Annotation ann) {
        for (Annotation subann: ann.annotationType().getAnnotations()) {
            if (subann.annotationType().equals(Stereotype.class)) {
                return true;
            }
        }
        return false;
    }

    private void producerFields(Class c, ProducerMap producerMap) {
        for (Field f : c.getDeclaredFields()) {
            if (containsProducingAnnotation(f.getAnnotations())) {
                producerMap.addToProducerMap(new QualifiedType(f));
            }
        }
    }

    private void producerMethods(Class c, ProducerMap producerMap) {
        for (Method m : c.getDeclaredMethods()) {
            if (containsProducingAnnotation(m.getAnnotations())) {
                producerMap.addToProducerMap(new QualifiedType(m));
            }
        }
    }

    private void beanWithoutProducer(final Class<?> c) {
        configuration
                .tobeStarted(c)
                .elseClass(c);
        innerClasses(c);
        injects(c);
        if (configuration.isTestClass(c)) {
            testClassAnnotation(c);
            sutClassAnnotation(c);
            classpathsAnnotations(c);
            packagesAnnotations(c);
            customAnnotations(c);
            enabledAlternatives(c);
            extraAnnotations(c);
            excludes(c);
        }
    }

    private void addToProducerMap(final Class<?> c, final ProducerMap producerMap) {
        producerMap.addToProducerMap(new QualifiedType(c));
        producerFields(c, producerMap);
        producerMethods(c, producerMap);
    }

    void work() {
        logger.trace("Phase1Analyzer starting");
        do {
            ArrayList<Class<?>> currentCandidates = new ArrayList<>();
            currentCandidates.addAll(configuration.getCandidates());
            configuration.getCandidates().clear();
            for (Class<?> c : currentCandidates) {
                if (handledCandidates.contains(c))
                    continue;
                logger.trace("evaluating {}", c);
                if (configuration.isExcluded(c)) {
                    logger.info("Excluded {}", c.getName());
                } else {
                    if (isInterceptingBean(c)) {
                        beanWithoutProducer(c);
                    } else if (ConfigStatics.mightBeBean(c)) {
                        beanWithoutProducer(c);
                        final ProducerMap producerMap = configuration.getProducerMap();
                        addToProducerMap(c, producerMap);
                    } else {
                        configuration.elseClass(c);
                    }
                }
                handledCandidates.add(c);
            }
        } while (configuration.getCandidates().size() > 0);
        for (Class<?> c : newAvailables) {
            addToProducerMap(c, configuration.getAvailableProducerMap());
        }
        logger.trace("Phase1Analyzer ready");
    }


}
