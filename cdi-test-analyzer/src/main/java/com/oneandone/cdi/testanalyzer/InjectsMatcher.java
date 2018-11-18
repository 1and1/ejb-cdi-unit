package com.oneandone.cdi.testanalyzer;

import org.apache.commons.lang3.reflect.TypeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.inject.Any;
import javax.enterprise.inject.Default;
import java.lang.annotation.Annotation;
import java.util.*;

/**
 * @author aschoerk
 */
public class InjectsMatcher {
    static Logger log = LoggerFactory.getLogger("InjectMatching");
    Map<QualifiedType, Set<QualifiedType>> matching = new HashMap<>();
    Map<QualifiedType, Set<QualifiedType>> ambiguus = new HashMap<>();
    Set<QualifiedType> empty = new HashSet<>();
    LeveledBuilder builder;

    public InjectsMatcher(final LeveledBuilder builder) {
        this.builder = builder;
    }

    public void match() {
        log.info("Starting matching");
        for (QualifiedType inject : builder.injections) {
            matchInject(inject);
        }
        log.info("Ready    matching");
    }

    public void matchInject(QualifiedType inject) {
        Set<QualifiedType> foundProducers = new HashSet<>();
        Set<QualifiedType> producers = builder.producerMap.get(inject.getRawtype());
        if (producers != null) {
            for (QualifiedType q : producers) {
                if (TypeUtils.isAssignable(q.getType(), inject.getType())) {
                    log.debug("Assignable Match \n --- {} \n --- to inject: {}", q, inject);
                    foundProducers.add(q);
                }
            }
        }
        // check types and qualifiers of results
        matching.put(inject, new HashSet<>());
        for (QualifiedType qp : foundProducers) {
            if (TypeUtils.isAssignable(qp.getType(), inject.getType())) {
                if (qualifiersMatch(inject, qp)) {
                    log.debug("Qualified Match \n --- {} \n --- to inject: {}", qp, inject);
                    matching.get(inject).add(qp);
                }
            }
        }
        handleAlternatives(matching.get(inject));
        if (matching.get(inject).size() == 0) {
            log.error("No match found for {}", inject);
            empty.add(inject);
            matching.remove(inject);
        } else if (matching.get(inject).size() > 1) {
            log.error("Ambiguus matches found for \n --- inject: {}", inject);
            for (QualifiedType x : matching.get(inject)) {
                log.info(" --- {}", x);
            }
            ambiguus.put(inject, matching.get(inject));
            matching.remove(inject);
        }
    }

    private void handleAlternatives(Set<QualifiedType> matching) {
        Optional<QualifiedType> optionalAlternative = matching.stream().filter(q -> q.isAlternative()).findAny();
        if (optionalAlternative.isPresent()) {
            QualifiedType alternative = optionalAlternative.get();
            if (alternative.getAlternativeStereotype() == null) {
                if (builder.enabledAlternatives.contains(alternative.getRawtype())) {
                    matching.clear();
                    matching.add(alternative);
                }

            }
        }

    }

    private Boolean qualifiersMatch(final QualifiedType qi, final QualifiedType qp) {
        if (qi.getQualifiers().isEmpty()) {
            if (hasDefault(qp.getQualifiers()) || hasAny(qp.getQualifiers())) {
                return true;
            } else
                return false;
        }
        if (qp.getQualifiers().isEmpty()) {
            if (qi.getQualifiers().size() <= 1 && hasDefault(qi.getQualifiers()))
                return true;
            else
                return false;
        }
        for (Annotation ai : qi.getQualifiers()) {
            if (ai.annotationType().getName().equals(Default.class.getName())) {
                if (!hasDefault(qp.getQualifiers())) {
                    return false;
                }
            } else {
                boolean found = false;
                for (Annotation ap : qp.getQualifiers()) {
                    if (ap.equals(ai)) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean hasDefault(final Set<Annotation> qualifiers) {
        if (qualifiers.isEmpty())
            return true;
        for (Annotation a : qualifiers) {
            if (a.annotationType().getName().equals(Default.class.getName()))
                return true;
        }
        return false;
    }

    private boolean hasAny(final Set<Annotation> qualifiers) {
        for (Annotation a : qualifiers) {
            if (a.annotationType().getName().equals(Any.class.getName()))
                return true;
        }
        return false;
    }

    public Set<Class<?>> evaluateMatches(final List<CdiConfigCreator.ProblemRecord> problems) {
        Set<Class<?>> newToBeStarted = new HashSet();
        for (QualifiedType inject : empty) {
            // search for producers and inner classes
        }

        for (QualifiedType inject : matching.keySet()) {
            final QualifiedType producingType = matching.get(inject).iterator().next();
            if (!builder.beansToBeStarted.contains(producingType.getDeclaringClass())) {
                newToBeStarted.add(producingType.getDeclaringClass());
            }
            builder.injectHandled(inject);
        }

        for (QualifiedType inject : ambiguus.keySet()) {
            Set<Class<?>> testClasses = new HashSet<>();
            Set<Class<?>> sutClasses = new HashSet<>();
            Set<Class<?>> availableTestClasses = new HashSet<>();
            Set<Class<?>> availableClasses = new HashSet<>();
            Set<QualifiedType> alternatives = new HashSet<>();
            Set<QualifiedType> producingTypes = ambiguus.get(inject);
            for (QualifiedType q : producingTypes) {
                Class declaringClass = q.getDeclaringClass();
                assert declaringClass != null;
                if (q.isAlternative()) {
                    if (q.getAlternativeStereotype() != null) {
                        if (builder.isActiveAlternativeStereoType(q.getAlternativeStereotype())) {
                            alternatives.add(q);
                        } else
                            continue;
                    } else if (builder.isAlternative(declaringClass)) {
                        alternatives.add(q);
                    } else {
                        continue;
                    }
                } else if (builder.isTestClass(declaringClass)) {
                    testClasses.add(declaringClass);
                } else if (builder.isSuTClass(declaringClass)) {
                    sutClasses.add(declaringClass);
                } else if (builder.isTestClassAvailable(declaringClass)) {
                    availableTestClasses.add(declaringClass);
                } else {
                    availableClasses.add(declaringClass);
                }
            }
            if (alternatives.size() != 0) {
                if (alternatives.size() > 1) {
                    problems.add(new CdiConfigCreator.ProblemRecord("Handling Inject: {} more than one active Alternative {} ",
                            inject, alternatives));
                }
                builder.injectHandled(inject);
            } else if (testClasses.size() != 0) {
                if (testClasses.size() > 1 || sutClasses.size() != 0) {
                    problems.add(new CdiConfigCreator.ProblemRecord("Handling Inject: {} Testclass(es) {} clashing with SutClass(es) {}",
                            inject, testClasses, sutClasses));
                } else {
                    final Class<?> testClass = testClasses.iterator().next();
                    if (!builder.beansToBeStarted.contains(testClass)) {
                        newToBeStarted.add(testClass);
                    }
                }
                builder.injectHandled(inject);
            } else if (sutClasses.size() > 0) {
                if (sutClasses.size() > 1)
                    problems.add(new CdiConfigCreator.ProblemRecord("Handling Inject: {} too many SutClass(es) {}",
                            inject, sutClasses));
                final Class<?> sutClass = sutClasses.iterator().next();
                if (!builder.beansToBeStarted.contains(sutClass)) {
                    newToBeStarted.add(sutClass);
                }
                builder.injectHandled(inject);
            } else if (availableTestClasses.size() > 0) {
                if (availableTestClasses.size() > 1)
                    problems.add(new CdiConfigCreator.ProblemRecord("Handling Inject: {} more than one available TestClass(es) {}",
                            inject, availableTestClasses));
                final Class<?> testClass = availableTestClasses.iterator().next();
                if (!builder.beansToBeStarted.contains(testClass)) {
                    builder.testClass(testClass);
                    newToBeStarted.add(testClass);
                }
            } else {
                assert (availableClasses.size() != 0);
                if (availableClasses.size() > 1) {
                    problems.add(new CdiConfigCreator.ProblemRecord("Handling Inject: {} more than one AvailableClass(es) {}",
                            inject, availableClasses));
                }
                final Class<?> toBeStarted = availableClasses.iterator().next();
                builder.sutClass(toBeStarted);
                newToBeStarted.add(toBeStarted);
            }

        }
        return newToBeStarted;
    }
}