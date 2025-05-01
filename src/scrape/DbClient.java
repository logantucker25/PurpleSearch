package scrape;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.neo4j.driver.Value;
import org.neo4j.driver.Values;

import api.InferenceConfig;

public class DbClient {

    private static Driver neo4jDriver;

    public static void initNeo4jConnection() {
        closeNeo4jDriver();
        String url = InferenceConfig.neo4jUrl;
        String user = InferenceConfig.neo4jUser;
        String pass = InferenceConfig.neo4jPassword;

        System.out.println("Connecting to Neo4j at: " + url + " (user=" + user + ")");
        neo4jDriver = GraphDatabase.driver(url, AuthTokens.basic(user, pass));
    }

    public static void closeNeo4jDriver() {
        if (neo4jDriver != null) {
            neo4jDriver.close();
            neo4jDriver = null;
        }
    }

    public static boolean isNeo4jAvailable() {
        if (neo4jDriver == null) {
            System.err.println("Neo4j driver not initialized. Call initNeo4jConnection() first.");
            return false;
        }
        try {
            neo4jDriver.verifyConnectivity();
            return true;
        } catch (Exception e) {
            System.err.println("Neo4j unavailable: " + e.getMessage());
            return false;
        }
    }

    public static Driver getNeo4jDriver() {
        return neo4jDriver;
    }

    // ---------------------------------------------------------------
    //                          graph calls
    // ---------------------------------------------------------------
    public static void insertFile(String filePath, String fileType) {
        String query = "MERGE (:File {path: $filePath, type: $fileType})";
        runQuery(query, Values.parameters("filePath", filePath, "fileType", fileType));
    }

    public static void insertDir(String dirName) {
        String query = "MERGE (:Dir {path: $dirname})";
        runQuery(query, Values.parameters("dirname", dirName));
    }

    public static void insertClass(String className) {
        String query = "MERGE (:Class {name: $className})";
        runQuery(query, Values.parameters("className", className));
    }

    public static void insertPackage(String pkgName) {
        String query = "MERGE (p:Pkg {name: $pkgName})";
        runQuery(query, Values.parameters("pkgName", pkgName));
    }

    public static void insertMethod(String methodName, String simpleName, String fileName, int startLine, int endLine, String methodCode) {
        String query = "MERGE (m:Method {name: $methodName}) "
                + "SET m.simple_name = $simpleName, m.file = $fileName, m.start_line = $startLine, m.end_line = $endLine, m.code = $methodCode";
        runQuery(query, Values.parameters("methodName", methodName,
                "simpleName", simpleName,
                "fileName", fileName,
                "startLine", startLine,
                "endLine", endLine,
                "methodCode", methodCode));
    }

    public static void insertMethod(String methodName) {
        String query = "MERGE (m:Method {name: $methodName})";
        runQuery(query, Values.parameters("methodName", methodName));
    }

    public static void ClassToConstructor(String className, String constructorName) {
        String query = "MATCH (c:Class {name: $className}), (m:Method {name: $constructorName}) "
                + "MERGE (c)-[:HAS_CONSTRUCTOR]->(m)";
        runQuery(query, Values.parameters("className", className, "constructorName", constructorName));
    }

    public static void insertConstructor(String constructorName, String simpleName, String fileName, int startLine, int endLine, String constructorCode) {
        String query = "MERGE (m:Method {name: $constructorName}) "
                + "SET m.simple_name = $simpleName, m.file = $fileName, m.start_line = $startLine, m.end_line = $endLine, m.code = $constructorCode";
        runQuery(query, Values.parameters("constructorName", constructorName,
                "simpleName", simpleName,
                "fileName", fileName,
                "startLine", startLine,
                "endLine", endLine,
                "constructorCode", constructorCode));
    }

    public static void ClassToField(String className, String variableName, String variableType, String initialValue) {
        String query = "MATCH (c:Class {name: $className}) "
                + "CREATE (f:Field {name: $variableName, type: $variableType, initialValue: $initialValue}) "
                + "MERGE (c)-[:HAS_FIELD]->(f)";
        runQuery(query, Values.parameters("className", className,
                "variableName", variableName,
                "variableType", variableType,
                "initialValue", initialValue));
    }

    public static void ClassToFieldToClass(String currentClassName, String fieldName, String fieldType, String initialValue) {
        String query = "MATCH (c:Class {name: $currentClassName}) "
                + "CREATE (f:Field {name: $fieldName, type: $fieldType, initialValue: $initialValue}) "
                + "MERGE (t:Class {name: $fieldType}) "
                + "MERGE (c)-[:HAS_FIELD]->(f) "
                + "MERGE (f)-[:HAS_TYPE]->(t)";
        runQuery(query, Values.parameters("currentClassName", currentClassName,
                "fieldName", fieldName,
                "fieldType", fieldType,
                "initialValue", initialValue));
    }

    public static void ClassExtendsClass(String className, String extendedTypeName) {
        String query = "MATCH (c:Class {name: $className}) "
                + "MERGE (e:Class {name: $extendedTypeName}) "
                + "MERGE (c)-[:EXTENDS]->(e)";
        runQuery(query, Values.parameters("className", className, "extendedTypeName", extendedTypeName));
    }

    public static void ClassImplementsClass(String className, String implementedTypeName) {
        String query = "MATCH (c:Class {name: $className}) "
                + "MERGE (i:Class {name: $implementedTypeName}) "
                + "MERGE (c)-[:IMPLEMENTS]->(i)";
        runQuery(query, Values.parameters("className", className, "implementedTypeName", implementedTypeName));
    }

    public static void insertImport(String importName) {
        String query = "MERGE (:Import {name: $importName})";
        runQuery(query, Values.parameters("importName", importName));
    }

    public static void insertXml(String filePath, String content) {
        String query = "CREATE (x:XML { path: $filePath, content: $content })";
        runQuery(query, Values.parameters("filePath", filePath, "content", content));
    }

    public static void dirToXml(String dirPath, String xmlFilePath) {
        String query = "MATCH (d:Dir {path: $dirPath}), (x:XML {path: $xmlFilePath}) "
                + "MERGE (d)-[:HAS_XML]->(x)";
        runQuery(query, Values.parameters("dirPath", dirPath, "xmlFilePath", xmlFilePath));
    }

    public static void FileToImport(String filePath, String importName) {
        String query = "MATCH (f:File {path: $filePath}), (c:Import {name: $importName}) "
                + "MERGE (f)-[:HAS_IMPORT]->(c)";
        runQuery(query, Values.parameters("filePath", filePath, "importName", importName));
    }

    public static void FileToClass(String filePath, String className) {
        String query = "MATCH (f:File {path: $filePath}), (c:Class {name: $className}) "
                + "MERGE (f)-[:HAS_CLASS]->(c)";
        runQuery(query, Values.parameters("filePath", filePath, "className", className));
    }

    public static void fileToPackage(String filePath, String pkgName) {
        String query = "MATCH (f:File {path: $filePath}), (p:Pkg {name: $packageName}) "
                + "MERGE (f)-[:IN_PACKAGE]->(p)";
        runQuery(query, Values.parameters("filePath", filePath, "packageName", pkgName));
    }

    public static void DirToDir(String dirName1, String dirName2) {
        String query = "MATCH (d1:Dir {path: $dirName1}), (d2:Dir {path: $dirName2}) "
                + "MERGE (d1)-[:HAS_DIR]->(d2)";
        runQuery(query, Values.parameters("dirName1", dirName1, "dirName2", dirName2));
    }

    public static void DirToFile(String dirName, String filePath) {
        String query = "MATCH (d:Dir {path: $dirName}), (f:File {path: $filePath}) "
                + "MERGE (d)-[:HAS_FILE]->(f)";
        runQuery(query, Values.parameters("dirName", dirName, "filePath", filePath));
    }

    public static void ClassToMethod(String className, String methodName) {
        String query = "MATCH (c:Class {name: $className}), (m:Method {name: $methodName}) "
                + "MERGE (c)-[:HAS_METHOD]->(m)";
        runQuery(query, Values.parameters("className", className, "methodName", methodName));
    }

    public static void MethodCallsMethod(String callerName, String calleeName) {
        String query = "MATCH (c:Method {name: $callerName}), (m:Method {name: $calleeName}) "
                + "MERGE (c)-[:CALLS_METHOD]->(m)";
        runQuery(query, Values.parameters("callerName", callerName, "calleeName", calleeName));
    }

    public static void MethodUsesClass(String methodName, String className) {
        String query = "MATCH (m:Method {name: $methodName}), (c:Class {name: $className}) "
                + "MERGE (m)-[:USES_CLASS]->(c)";
        runQuery(query, Values.parameters("methodName", methodName, "className", className));
    }

    public static void ConstructorUsesClass(String constructorName, String className) {
        String query = "MATCH (m:Method {name: $constructorName}), (c:Class {name: $className}) "
                + "MERGE (m)-[:USES_CLASS]->(c)";
        runQuery(query, Values.parameters("constructorName", constructorName, "className", className));
    }

    public static void runQuery(String query, Value parameters) {
        try (Session session = neo4jDriver.session()) {
            session.run(query, parameters);
        } catch (Exception e) {
            System.err.println("Neo4j Query Error: " + e.getMessage());
        }
    }

    public static void runQuery(String query) {
        try (Session session = neo4jDriver.session()) {
            session.run(query);
        } catch (Exception e) {
            System.err.println("Neo4j Query Error:" + e.getMessage());
        }
    }

}
