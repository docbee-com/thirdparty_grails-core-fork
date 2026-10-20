/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
package org.grails.datastore.gorm.jdbc.sqltail

import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.context.request.RequestAttributes
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import spock.lang.Specification

class SqlTailRequestAttributionSpec extends Specification {

    SqlTailRequestAttribution attribution = new SqlTailRequestAttribution()

    void cleanup() {
        RequestContextHolder.resetRequestAttributes()
    }

    void "a statement outside a web request is attributed to its thread"() {
        when:
        SqlTailRecord record = attribution.attribute(new SqlTailRecord(sql: 'select 1'))

        then:
        record.requestId == null
        record.request == null
        record.page == "thread:${Thread.currentThread().name}"
        !record.webRequest
    }

    void "a statement in a web request is attributed to the controller and action serving it"() {
        given:
        bindRequest('GET', '/book/show/42', controller: 'book', action: 'show')

        when:
        SqlTailRecord record = attribution.attribute(new SqlTailRecord(sql: 'select 1'))

        then:
        record.requestId == 'r1'
        record.page == 'book/show'
        record.request == 'GET /book/show/42'
        record.webRequest
    }

    void "every statement of a request shares its id and the next request gets the next id"() {
        given:
        bindRequest('GET', '/book/index', controller: 'book', action: 'index')

        when:
        List<String> first = (1..3).collect { attribution.attribute(new SqlTailRecord(sql: 'select 1')).requestId }
        bindRequest('POST', '/book/save', controller: 'book', action: 'save')
        String second = attribution.attribute(new SqlTailRecord(sql: 'insert into book (id) values (?)')).requestId

        then:
        first == ['r1', 'r1', 'r1']
        second == 'r2'
    }

    void "the page carries the controller namespace and copes with a missing action"() {
        given:
        bindRequest('GET', '/admin/book', controller: 'book', namespace: 'admin')

        expect:
        attribution.attribute(new SqlTailRecord(sql: 'select 1')).page == 'admin:book'
    }

    void "a request that has not reached a controller yet has no page"() {
        given:
        bindRequest('GET', '/health')

        when:
        SqlTailRecord record = attribution.attribute(new SqlTailRecord(sql: 'select 1'))

        then:
        record.page == null
        record.requestId == 'r1'
        record.request == 'GET /health'
    }

    void "a request that is not a servlet request is attributed to its page but has no request line"() {
        given: 'request attributes of another kind, as a framework other than Spring MVC binds'
        Map<String, Object> store = [
                (SqlTailRequestAttribution.CONTROLLER_NAME_ATTRIBUTE): 'book',
                (SqlTailRequestAttribution.ACTION_NAME_ATTRIBUTE): 'list',
        ]
        RequestContextHolder.setRequestAttributes([
                getAttribute: { String name, int scope -> store[name] },
                setAttribute: { String name, Object value, int scope -> store[name] = value },
        ] as RequestAttributes)

        when:
        List<SqlTailRecord> records = (1..2).collect { attribution.attribute(new SqlTailRecord(sql: 'select 1')) }

        then: 'the id is kept on the request, as it is on a servlet request'
        records*.requestId == ['r1', 'r1']
        records*.page == ['book/list', 'book/list']
        records*.request == [null, null]
    }

    void "without Spring Web on the classpath every statement is attributed to its thread"() {
        given: 'a request bound on the thread, which the attribution cannot see without Spring Web'
        bindRequest('GET', '/book/show/42', controller: 'book', action: 'show')

        when:
        SqlTailRecord record = attributionWithout('org.springframework.web.').attribute(new SqlTailRecord(sql: 'select 1'))

        then:
        record.requestId == null
        record.request == null
        record.page == "thread:${Thread.currentThread().name}"
        !record.webRequest
    }

    void "without the servlet API on the classpath a statement is attributed to its request, which has no request line"() {
        given:
        bindRequest('GET', '/book/show/42', controller: 'book', action: 'show')

        when:
        SqlTailRecord record = attributionWithout('jakarta.servlet.').attribute(new SqlTailRecord(sql: 'select 1'))

        then:
        record.requestId == 'r1'
        record.page == 'book/show'
        record.request == null
    }

    private static void bindRequest(Map<String, String> attributes = [:], String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri)
        if (attributes.controller) {
            request.setAttribute(SqlTailRequestAttribution.CONTROLLER_NAME_ATTRIBUTE, attributes.controller)
        }
        if (attributes.action) {
            request.setAttribute(SqlTailRequestAttribution.ACTION_NAME_ATTRIBUTE, attributes.action)
        }
        if (attributes.namespace) {
            request.setAttribute(SqlTailRequestAttribution.CONTROLLER_NAMESPACE_ATTRIBUTE, attributes.namespace)
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request))
    }

    /**
     * An attribution loaded as an application without a library on its classpath loads it: by a class loader
     * that cannot see the library's package. The records it fills in are the usual class, loaded once.
     */
    private static Object attributionWithout(String hiddenPackage) {
        ClassLoader loader = new HidingClassLoader(SqlTailRequestAttribution.classLoader, hiddenPackage)
        loader.loadClass(SqlTailRequestAttribution.name).getConstructor().newInstance()
    }

    /**
     * Defines the attribution class and its nested classes itself, so that their lookups of the hidden
     * package come to this loader; every other class comes from the parent.
     */
    private static class HidingClassLoader extends ClassLoader {

        private final String hiddenPackage

        HidingClassLoader(ClassLoader parent, String hiddenPackage) {
            super(parent)
            this.hiddenPackage = hiddenPackage
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith(hiddenPackage)) {
                throw new ClassNotFoundException(name)
            }
            if (name != SqlTailRequestAttribution.name && !name.startsWith(SqlTailRequestAttribution.name + '$')) {
                return super.loadClass(name, resolve)
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> defined = findLoadedClass(name)
                if (defined == null) {
                    byte[] bytes = parent.getResourceAsStream(name.replace('.', '/') + '.class').bytes
                    // the usual class's protection domain, so that the class has a code location: coverage
                    // tooling instruments a class without one only when asked to
                    defined = defineClass(name, bytes, 0, bytes.length, SqlTailRequestAttribution.protectionDomain)
                }
                if (resolve) {
                    resolveClass(defined)
                }
                return defined
            }
        }
    }
}
