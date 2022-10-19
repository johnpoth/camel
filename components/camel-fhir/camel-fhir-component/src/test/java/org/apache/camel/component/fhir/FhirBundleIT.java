/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.component.fhir;

import java.io.ByteArrayInputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import ca.uhn.fhir.rest.api.PreferReturnEnum;
import org.apache.camel.builder.RouteBuilder;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.DateType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Test class for {@link org.apache.camel.component.fhir.api.FhirUpdate} APIs. The class source won't be generated again
 * if the generator MOJO finds it under src/test/java.
 */
public class FhirBundleIT extends AbstractFhirTestSupport {

    @Test
    public void testBundle() throws Exception {

        //Construct dummy bundle
        Date date = new SimpleDateFormat("yyyy-MM-dd").parse("1998-04-29");
        assertNotEquals(date, patient.getBirthDate());
        this.patient.setBirthDate(date);
        final Map<String, Object> headers = new HashMap<>();
        // parameter type is org.hl7.fhir.instance.model.api.IBaseResource
        headers.put("CamelFhir.resourceAsString", new ByteArrayInputStream(this.fhirContext.newJsonParser().encodeResourceToString(this.patient).getBytes()));
        // parameter type is org.hl7.fhir.instance.model.api.IIdType
        headers.put("CamelFhir.id", this.patient.getIdElement());
        // parameter type is ca.uhn.fhir.rest.api.PreferReturnEnum
        headers.put("CamelFhir.preferReturn", PreferReturnEnum.REPRESENTATION);
        this.patient.setBirthDateElement(new DateType("2000-12-31"));
        Bundle bundle = new Bundle();
        bundle.addEntry().setResource(this.patient);

        // rest call
        given()
                .header("Content-Type","application/json")
                .body(this.fhirContext.newJsonParser().encodeResourceToString(bundle))
                .when()
                .post("/bundle")
                .then()
                .statusCode(200);
    }

    @Override
    protected RouteBuilder createRouteBuilder() {
        return new RouteBuilder() {
            public void configure() {
                // REST service
                rest()
                        .post("/bundle").consumes("application/json")
                        .to("direct://RESOURCE_BUNDLE");
                // JAVA route
                from("direct://RESOURCE_BUNDLE")
                        .split().jsonpathWriteAsString("$.entry[*].resource")
                        .unmarshal().fhirJson()
                        .to("fhir://update/resource?inBody=resource");
            }
        };
    }
}
