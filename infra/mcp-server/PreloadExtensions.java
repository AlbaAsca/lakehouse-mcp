import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/** Build step of the image. Arguments: the extension directory, then the extensions to install in it. */
class PreloadExtensions {

    public static void main(String[] args) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:");
                Statement statement = connection.createStatement()) {
            statement.execute("SET extension_directory = '" + args[0] + "'");
            for (int i = 1; i < args.length; i++) {
                statement.execute("INSTALL " + args[i]);
            }
        }
    }
}
