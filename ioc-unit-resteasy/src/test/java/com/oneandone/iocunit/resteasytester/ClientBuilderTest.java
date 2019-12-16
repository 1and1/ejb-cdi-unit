package com.oneandone.iocunit.resteasytester;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import javax.inject.Inject;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.junit.Test;
import org.junit.runner.RunWith;

import com.oneandone.iocunit.IocUnitRunner;
import com.oneandone.iocunit.analyzer.annotations.SutClasses;
import com.oneandone.iocunit.resteasytester.resources.ExampleResource;

/**
 * @author aschoerk
 */
@RunWith(IocUnitRunner.class)
@SutClasses({ExampleErrorMapper.class, ExampleResource.class})
public class ClientBuilderTest {

    @Inject
    ClientBuilder clientBuilder;

    @Test
    public void test() {
        WebTarget wt = clientBuilder.build().target("/");
        Response invoke = wt
                .path("/restpath/method1")
                .request(MediaType.APPLICATION_JSON)
                .buildGet().invoke();
        assertThat(invoke.getStatus(), is(200));
    }
}