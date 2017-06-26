package com.oneandone.ejbcdiunit.persistence;

import java.util.HashSet;
import java.util.Map;
import java.util.Stack;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.ejb.TransactionAttributeType;
import javax.enterprise.context.ApplicationScoped;
import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.Persistence;
import javax.sql.DataSource;
import javax.transaction.UserTransaction;

import org.apache.commons.dbcp2.BasicDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.oneandone.ejbcdiunit.SupportEjbExtended;
import com.oneandone.ejbcdiunit.resourcesimulators.SimulatedUserTransaction;


/**
 * The PersistenceFactory provides {@link EntityManager}s for tests.
 * Additionally this PersistenceFactory allows to initiate Transactions according to @see TransactionAttributeType
 * so that normal CDI-Test-Environments can simulate EJB-Transaction-Handling.
 *
 * @author aschoerkfalse)
 */
@SupportEjbExtended
@ApplicationScoped
public abstract class PersistenceFactory {

    private static final HashSet<String> PERSISTENCE_UNIT_NAMES = new HashSet<>();

    private final Logger logger = LoggerFactory.getLogger(PersistenceFactory.class);
    private final ThreadLocal<Stack<EntityManager>> emStackThreadLocal = new ThreadLocal<>();
    private EntityManagerFactory emf = null;

    protected abstract String getPersistenceUnitName();

    private SimulatedTransactionManager transactionManager = new SimulatedTransactionManager();

    private ThreadLocal<Stack<EntityManager>> getEmStackThreadLocal() {
        return emStackThreadLocal;
    }

    private EntityManagerFactory getEmf() {
        return emf;
    }

    private void setEmf(EntityManagerFactory emfP) {
        this.emf = emfP;
    }

    private ConcurrentLinkedQueue<Stack<EntityManager>> threadlocalStacks = new ConcurrentLinkedQueue<>();

    /**
     * prepare EntityManagerFactory
     */
    @PostConstruct
    public void construct() {
        logger.info("creating persistence factory {}", getPersistenceUnitName());
        synchronized (PERSISTENCE_UNIT_NAMES) {
            if (PERSISTENCE_UNIT_NAMES.contains(getPersistenceUnitName())) {
                throw new RuntimeException("Repeated construction of currently existing PersistenceFactory for " + getPersistenceUnitName());
            } else {
                setEmf(Persistence.createEntityManagerFactory(getPersistenceUnitName()));
                PERSISTENCE_UNIT_NAMES.add(getPersistenceUnitName());
            }
        }
    }

    /**
     * make sure all connections will be closed
     */
    @PreDestroy
    public void destroy() {
        logger.info("destroying persistence factory {}", getPersistenceUnitName());
        synchronized (PERSISTENCE_UNIT_NAMES) {
            if (!PERSISTENCE_UNIT_NAMES.contains(getPersistenceUnitName())) {
                throw new RuntimeException("Expected PersistenceFactory for " + getPersistenceUnitName());
            } else {
                for (Stack<EntityManager> stack : threadlocalStacks) {
                    for (EntityManager em : stack) {
                        if (em.getTransaction().isActive()) {
                            try {
                                em.getTransaction().rollback();
                            } catch (Throwable thw) {
                                logger.error("Throwable during closing of pending transaction", thw);
                            }
                        }
                        em.close();
                    }
                    stack.clear();
                }
                if (getEmf() != null && getEmf().isOpen()) {
                    getEmf().close();
                }
                PERSISTENCE_UNIT_NAMES.remove(getPersistenceUnitName());
            }
        }
    }

    /**
     * Looks for the current entity manager and returns it. If no entity manager was found, this method logs a warn
     * message and returns null. This will cause a NullPointerException in most cases and will cause a stack trace
     * starting from your service method.
     *
     * @return the currently used entity manager on top of stack. Don't use this in producers!
     */
    EntityManager getTransactional() {
        transactionManager.takePart(this);
        return get();
    }

    /**
     * Looks for the current entity manager and returns it. If no entity manager was found, this method logs a warn
     * message and returns null. This will cause a NullPointerException in most cases and will cause a stack trace
     * starting from your service method.
     *
     * @return the currently used entity manager on top of stack. Don't use this in producers!
     */
    EntityManager get() {
        final Stack<EntityManager> entityManagerStack = getEmStackThreadLocal().get();
        if (entityManagerStack == null || entityManagerStack.isEmpty()) {
            createAndRegister();
        }
        return getEmStackThreadLocal().get().peek();
    }


    /**
     * Creates an entity manager and stores it in a stack. The use of a stack allows to implement transaction with a 'requires new' behaviour.
     *
     */
    void createAndRegister() {
        logger.trace("Creating and registering an entity manager for " + Thread.currentThread().getName());
        Stack<EntityManager> entityManagerStack = getEmStackThreadLocal().get();
        if (entityManagerStack == null) {
            entityManagerStack = new Stack<>();
            getEmStackThreadLocal().set(entityManagerStack);
            threadlocalStacks.add(entityManagerStack);
        }

        final EntityManager entityManager = getEmf().createEntityManager();
        entityManagerStack.push(entityManager);
    }

    /**
     * Removes the current entity manager from the thread local stack.
     *
     * @throws IllegalStateException
     *             in case the entity manager was not found on the stack
     */
    void unRegister() {
        logger.trace("UnRegistering an entity manager");
        final Stack<EntityManager> entityManagerStack = getEmStackThreadLocal().get();
        if (entityManagerStack == null || entityManagerStack.isEmpty()) {
            throw new IllegalStateException("Removing of entity manager failed. Your entity manager was not found.");
        }

        final EntityManager entityManager = entityManagerStack.pop();
        if (entityManager.getTransaction().isActive()) {
            // entityManager.getTransaction().commit();
            // logger.info("Dropping EntityManager with active Transaction");
            throw new IllegalStateException("Popping with active transaction");
        }
    }

    /**
     * Create a transaction-object as Java-Resource. The current entityManager is accordingly handled
     * @param transactionAttribute allows to simulate the ejb-transaction-handling
     * @return a resource-object that handles the transaction in the block correctly.
     */
    public TestTransaction transaction(TransactionAttributeType transactionAttribute) {
        return new TestTransaction(transactionAttribute);
    }

    /**
     * Create a transaction-object as Java-Resource. The current entityManager is accordingly handled
     * @param transactionAttribute allows to simulate the ejb-transaction-handling
     * @param runnable the code to be done during the transaction.
     */
    public void transaction(TransactionAttributeType transactionAttribute, TestClosure runnable) {
        try (TestTransaction tra = transaction(transactionAttribute)) {
            try {
                runnable.execute();
            } catch (Throwable e) {
                throw new TestTransactionException(e);
            }
        } catch (Exception e) {
            throw new TestTransactionException(e);
        }
    }


    /**
     *
     * @return Usertransaction injectable
     */
    public UserTransaction produceUserTransaction() {
        return new SimulatedUserTransaction();
    }

    /**
     * returns EntityManager, to be injected and used so that the current threadSpecific context is correctly handled
     *
     * @return the EntityManager as it is returnable by producers.
     */
    public EntityManager produceEntityManager() {
        return new EntityManagerDelegate(this);
    }

    /**
     * create a jdbc-Datasource using the same driver url user and password as the entityManager
     *
     * @return a jdbc-Datasource using the same driver url user and password as the entityManager
     */
    public DataSource produceDataSource() {
        BasicDataSource newDataSource = new BasicDataSource();
        Map props = emf.getProperties();
        newDataSource.setDriverClassName((String) props.get("javax.persistence.jdbc.driver"));
        newDataSource.setUrl((String) props.get("javax.persistence.jdbc.url"));
        newDataSource.setUsername((String) props.get("javax.persistence.jdbc.user"));
        newDataSource.setPassword((String) props.get("javax.persistence.jdbc.password"));
        return newDataSource;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }

        PersistenceFactory that = (PersistenceFactory) obj;

        return getPersistenceUnitName() != null ? getPersistenceUnitName().equals(that.getPersistenceUnitName())
                : that.getPersistenceUnitName() == null;
    }

    @Override
    public int hashCode() {
        return getPersistenceUnitName() != null ? getPersistenceUnitName().hashCode() : 0;
    }
}