import java.awt.Desktop;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public class OpenMeshQuery {
    public static final String DEFAULT_BASE = "https://ui.mesh-java.test.ecp/graphiql/index.html";
    public static final String UAT_BASE = "https://ui.mesh-java.uat.ecp/graphiql/index.html";

    public static URI queryUrl(String query, String variablesJson) {
        return queryUrl(DEFAULT_BASE, query, variablesJson);
    }

    public static URI queryUrl(String base, String query, String variablesJson) {
        return URI.create(base + "?query="
            + URLEncoder.encode(query, StandardCharsets.UTF_8)
            + "&variables=" + URLEncoder.encode(variablesJson, StandardCharsets.UTF_8));
    }

    public static void main(String[] args) throws Exception {
        boolean uat = false;
        boolean print = false;
        for (String arg : args) {
            if ("--uat".equals(arg)) uat = true;
            if ("--print".equals(arg)) print = true;
        }
        URI link = queryUrl(uat ? UAT_BASE : DEFAULT_BASE, "query FromJava { __typename }", "{}");
        if (print) {
            System.out.println(link);
        } else {
            Desktop.getDesktop().browse(link);
        }
    }
}
