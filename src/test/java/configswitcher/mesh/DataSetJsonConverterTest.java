package configswitcher.mesh;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import configswitcher.mesh.service.DataSetJsonConverter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class DataSetJsonConverterTest {

    @Test
    public void testConvertRealDataSetFormat() {
        String dataSetInput = "{data={ruListen2uPsAuthzApi_AuthzService_GetUserInfoWithRoles=" +
                "{userInfo={id=eec9d22d-6124-4e97-9e88-17f2d2a956d3, personId=null, email=null, " +
                "mobilePhone=null, userStatus=ACTIVE, externalSystem=KEYCLOAK, companyId=null, " +
                "departmentId=null, timeZone=null, isAutoAdded=false, firstName=Тест, lastName=Тестов, " +
                "patronymic=Тестович, snils=null, createdDate=null, updateDate=null}, " +
                "roles=[{id=2, name=user, externalSystem=KEYCLOAK, isAutoAdded=false, createDate=null, updateDate=null}]}}}";

        String json = DataSetJsonConverter.toPrettyJson(dataSetInput);
        assertNotNull(json);
        assertFalse(json.isBlank());

        // Validate it parses as valid JSON
        JsonElement root = JsonParser.parseString(json);
        assertTrue(root.isJsonObject());
        JsonObject rootObj = root.getAsJsonObject();
        assertTrue(rootObj.has("data"));

        JsonObject dataObj = rootObj.getAsJsonObject("data");
        JsonObject serviceObj = dataObj.getAsJsonObject("ruListen2uPsAuthzApi_AuthzService_GetUserInfoWithRoles");
        assertNotNull(serviceObj);

        JsonObject userObj = serviceObj.getAsJsonObject("userInfo");
        assertEquals("eec9d22d-6124-4e97-9e88-17f2d2a956d3", userObj.get("id").getAsString());
        assertEquals("ACTIVE", userObj.get("userStatus").getAsString());
        assertEquals("Тест", userObj.get("firstName").getAsString());
        assertEquals("Тестов", userObj.get("lastName").getAsString());
        assertFalse(userObj.get("isAutoAdded").getAsBoolean());
        assertTrue(userObj.get("personId").isJsonNull());

        assertTrue(serviceObj.has("roles"));
        assertTrue(serviceObj.get("roles").isJsonArray());
        JsonObject role0 = serviceObj.get("roles").getAsJsonArray().get(0).getAsJsonObject();
        assertEquals(2, role0.get("id").getAsInt());
        assertEquals("user", role0.get("name").getAsString());
    }

    @Test
    public void testStandardJsonPassThrough() {
        String standardJson = "{\"data\":{\"count\":42,\"status\":\"OK\",\"items\":[\"a\",\"b\"]}}";
        String pretty = DataSetJsonConverter.toPrettyJson(standardJson);
        assertTrue(pretty.contains("\"count\": 42"));
        assertTrue(pretty.contains("\"status\": \"OK\""));
        assertTrue(pretty.contains("\"items\": ["));
    }

    @Test
    public void testEmptyAndNull() {
        assertEquals("", DataSetJsonConverter.toPrettyJson(null));
        assertEquals("", DataSetJsonConverter.toPrettyJson(""));
        assertEquals("plain text", DataSetJsonConverter.toPrettyJson("plain text"));
    }
}
