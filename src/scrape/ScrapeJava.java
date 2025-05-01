package scrape;

import java.io.File;              // <-- Our unified AI client
import java.io.FileInputStream;      // <-- Holds the front-end config (llmProvider, llmModel, etc.)
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;

public class ScrapeJava {

    private static PrintWriter errorLogWriter;
    private static PrintWriter loadLogWriter;
    private static final CombinedTypeSolver globalTypeSolver = new CombinedTypeSolver();
    private static CompilationUnit cu;
    private static JavaParser psr;

    // --------------------------------------
    // A. init solver 
    // --------------------------------------
    public static void initializeTypeSolver(File projectRoot) {

        try {
            errorLogWriter = new PrintWriter(new FileWriter("scrape_errors.log", true), true);
        } catch (IOException e) {
            System.err.println("Failed to initialize error scrape error log: " + e.getMessage());
            errorLogWriter = new PrintWriter(System.err);
        }

        try {
            loadLogWriter = new PrintWriter(new FileWriter("loading.log", true), true);
        } catch (IOException e) {
            System.err.println("Failed to initialize error loading log: " + e.getMessage());
        }
        loadLogWriter.println("Turning your files into a graph");

        AtomicBoolean foundSource = new AtomicBoolean(false);
        Path root = projectRoot.toPath();

        try (Stream<Path> paths = Files.walk(root)) {
            paths
                    .filter(Files::isDirectory)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.equals("java") || name.equals("src");
                    })
                    .forEach(p -> {
                        loadLogWriter.println("Found source root: " + p.toAbsolutePath());
                        foundSource.set(true);
                        globalTypeSolver.add(new JavaParserTypeSolver(p.toFile()));
                    });
        } catch (IOException e) {
            System.err.println("Error walking project tree for type solver: " + e.getMessage());
        }

        if (!foundSource.get()) {
            globalTypeSolver.add(new JavaParserTypeSolver(projectRoot));
        }
    }

    // --------------------------------------
    // B. Main Entry
    // --------------------------------------
    public static void processDir(File dir) {

        String dirPath = dir.getAbsolutePath();
        DbClient.insertDir(dirPath);

        File parent = dir.getParentFile();
        if (parent != null && parent.isDirectory()) {
            String parentDirPath = parent.getAbsolutePath();
            DbClient.DirToDir(parentDirPath, dirPath);
        }
    }

    public static void processJavaFile(File file) {

        String filePath = file.getAbsolutePath();
        DbClient.insertFile(filePath, "java");

        loadLogWriter.println("processing: " + file.getName());
        errorLogWriter.println("\n\n\nCURRENTLY PARSING FILE --> " + filePath);

        String parentDir = file.getParentFile().getAbsolutePath();
        DbClient.DirToFile(parentDir, filePath);

        try (FileInputStream in = new FileInputStream(file)) {

            JavaSymbolSolver symbolSolver = new JavaSymbolSolver(globalTypeSolver);
            ParserConfiguration config = new ParserConfiguration().setSymbolResolver(symbolSolver);

            StaticJavaParser.setConfiguration(config);

            if (psr == null) {
                psr = new JavaParser();
            }

            cu = psr.parse(in).getResult().orElse(null);
            symbolSolver.inject(cu);

            if (cu == null) {
                errorLogWriter.println("FAILED to parse jave file at AST stage: " + file.getName());
                return;
            }

            cu.getPackageDeclaration().ifPresent(pkg -> {
                String pkgName = pkg.getNameAsString();
                DbClient.insertPackage(pkgName);
                DbClient.fileToPackage(filePath, pkgName);
            });

            cu.getImports().forEach(importDecl -> {
                String name = importDecl.getNameAsString();
                DbClient.insertImport(name);
                DbClient.FileToImport(filePath, name);
            });

            cu.accept(new FullASTVisitor(filePath), null);

        } catch (Exception e) {
            errorLogWriter.println("ERROR parsing Java: " + e.getMessage());
        }
    }

    private static class FullASTVisitor extends VoidVisitorAdapter<Void> {

        private final String filePath;
        private ClassOrInterfaceDeclaration currentClassOrInterface = null;
        private MethodDeclaration currentMethod = null;
        private Object currentExecutable = null;

        public FullASTVisitor(String filePath) {
            this.filePath = filePath;
        }

        @Override
        public void visit(ClassOrInterfaceDeclaration classDecl, Void arg) {
            currentClassOrInterface = classDecl;
            final String classId = sanitaizeId(currentClassOrInterface.resolve().getQualifiedName());

            DbClient.insertClass(classId);
            DbClient.FileToClass(filePath, classId);

            currentClassOrInterface.getExtendedTypes().forEach(extendedType -> {
                try {
                    ResolvedType resolvedExtendedType = extendedType.resolve();
                    String extendedTypeQualifiedName;
                    if (resolvedExtendedType.isReferenceType()) {
                        extendedTypeQualifiedName = resolvedExtendedType.asReferenceType().getQualifiedName();
                    } else {
                        extendedTypeQualifiedName = resolvedExtendedType.describe();
                    }
                    DbClient.ClassExtendsClass(classId, extendedTypeQualifiedName);
                } catch (Exception e) {
                    errorLogWriter.println("Error resolving extended type '" + extendedType.getNameAsString() + " : " + e.getMessage());
                }
            });

            currentClassOrInterface.getImplementedTypes().forEach(implementedType -> {
                try {
                    ResolvedType resolvedImplementedType = implementedType.resolve();
                    String implementedTypeQualifiedName;
                    if (resolvedImplementedType.isReferenceType()) {
                        implementedTypeQualifiedName = resolvedImplementedType.asReferenceType().getQualifiedName();
                    } else {
                        implementedTypeQualifiedName = resolvedImplementedType.describe();
                    }
                    DbClient.ClassImplementsClass(classId, implementedTypeQualifiedName);
                } catch (Exception e) {
                    errorLogWriter.println("Error resolving implemented type '" + implementedType.getNameAsString() + " : " + e.getMessage());
                }
            });
            super.visit(classDecl, arg);
        }

        @Override
        public void visit(MethodDeclaration methodDecl, Void arg) {
            currentExecutable = methodDecl;
            currentMethod = methodDecl;
            String currentMethodSignature = "";
            try {
                currentMethodSignature = methodDecl.resolve().getQualifiedSignature();
            } catch (UnsolvedSymbolException e) {
                errorLogWriter.println("UnsolvedSymbolException: cannot resolve method" + currentMethod + " : " + e.getMessage());
                return;
            }

            String methodId = sanitaizeId(currentMethodSignature);

            int startLine = methodDecl.getRange().get().begin.line;
            int endLine = methodDecl.getRange().get().end.line;
            String methodCode = methodDecl.getSignature().asString() + methodDecl.getBody().map(b -> b.toString()).orElse("");
            String simpleMethodName = methodDecl.getNameAsString() + "()";

            DbClient.insertMethod(methodId, simpleMethodName, filePath, startLine, endLine, methodCode);
            DbClient.ClassToMethod(currentClassOrInterface.resolve().getQualifiedName(), methodId);

            super.visit(methodDecl, arg);
        }

        @Override
        public void visit(ConstructorDeclaration constructorDecl, Void arg) {
            currentExecutable = constructorDecl;
            String constructorSignature = "";
            try {
                constructorSignature = constructorDecl.resolve().getQualifiedSignature();
            } catch (UnsolvedSymbolException e) {
                errorLogWriter.println("UnsolvedSymbolException: cannot resolve constructor " + constructorDecl + " : " + e.getMessage());
                return;
            }

            String constructorId = sanitaizeId(constructorSignature);

            int startLine = constructorDecl.getRange().get().begin.line;
            int endLine = constructorDecl.getRange().get().end.line;
            String constructorCode = constructorDecl.getSignature().asString() + (constructorDecl.getBody() != null ? constructorDecl.getBody().toString() : "");
            String simpleConstructorName = constructorDecl.getNameAsString() + "()";

            DbClient.insertConstructor(constructorId, simpleConstructorName, filePath, startLine, endLine, constructorCode);
            DbClient.ClassToConstructor(currentClassOrInterface.resolve().getQualifiedName(), constructorId);

            super.visit(constructorDecl, arg);
        }

        @Override
        public void visit(FieldDeclaration fieldDecl, Void arg) {
            String className = currentClassOrInterface.resolve().getQualifiedName();

            for (VariableDeclarator varDecl : fieldDecl.getVariables()) {
                String variableName = varDecl.getNameAsString();
                String initialValue = varDecl.getInitializer().isPresent()
                        ? varDecl.getInitializer().get().toString() : "";

                if (varDecl.getType().isPrimitiveType()) {
                    String typeString = varDecl.getType().asString();
                    DbClient.ClassToField(className, variableName, typeString, initialValue);
                } else {
                    try {
                        ResolvedType resolvedType = varDecl.getType().resolve();
                        if (resolvedType.isReferenceType()) {
                            String qualifiedName = resolvedType.asReferenceType().getQualifiedName();
                            if (qualifiedName.startsWith("java.") || qualifiedName.startsWith("javax.")) {
                                DbClient.ClassToField(className, variableName, qualifiedName, initialValue);
                            } else {
                                DbClient.ClassToFieldToClass(className, variableName, qualifiedName, initialValue);
                            }
                        }
                    } catch (Exception e) {
                        errorLogWriter.println("Error resolving type for field " + variableName + " : " + e.getMessage());
                    }
                }
            }

            super.visit(fieldDecl, arg);
        }

        @Override
        public void visit(NameExpr nameExpr, Void arg) {
            if (currentExecutable != null) {
                try {
                    ResolvedType resolvedType = nameExpr.calculateResolvedType();
                    if (resolvedType.isReferenceType()) {
                        String typeQualifiedName = resolvedType.asReferenceType().getQualifiedName();
                        if (!(typeQualifiedName.startsWith("java.") || typeQualifiedName.startsWith("javax."))) {

                            DbClient.insertClass(typeQualifiedName);

                            String callerSignature;
                            String callerId;
                            if (currentExecutable instanceof MethodDeclaration) {
                                callerSignature = ((MethodDeclaration) currentExecutable).resolve().getQualifiedSignature();
                                callerId = sanitaizeId(callerSignature);
                                DbClient.MethodUsesClass(callerId, typeQualifiedName);
                            } else if (currentExecutable instanceof ConstructorDeclaration) {
                                callerSignature = ((ConstructorDeclaration) currentExecutable).resolve().getQualifiedSignature();
                                callerId = sanitaizeId(callerSignature);
                                DbClient.ConstructorUsesClass(callerId, typeQualifiedName);
                            } else {
                                callerSignature = "unknown";
                            }
                        }
                    }
                } catch (Exception e) {
                    errorLogWriter.println("Error resolving variable usage '" + nameExpr + " : " + e.getMessage());
                }
            }
            super.visit(nameExpr, arg);
        }

        @Override
        public void visit(MethodCallExpr methodCall, Void arg) {
            if (currentMethod != null) {

                String calledMethodSignature = "";
                String currentMethodSignature = "";

                try {
                    calledMethodSignature = methodCall.resolve().getQualifiedSignature();
                } catch (UnsolvedSymbolException e) {
                    errorLogWriter.println("UnsolvedSymbolException: cannot resolve method call " + methodCall + " : " + e.getMessage());
                }

                try {
                    currentMethodSignature = currentMethod.resolve().getQualifiedSignature();
                } catch (UnsolvedSymbolException e) {
                    errorLogWriter.println("UnsolvedSymbolException: cannot resolve method call " + currentMethod + " : " + e.getMessage());
                }

                String currentMethodId = sanitaizeId(currentMethodSignature);
                String calledMethodId = sanitaizeId(calledMethodSignature);

                if (calledMethodId != "") {
                    DbClient.insertMethod(calledMethodId);
                    DbClient.MethodCallsMethod(currentMethodId, calledMethodId);
                }
            }
        }
    }

    private static String sanitaizeId(String id) {
        String sanitizedString = id.replace(" ", "\s");
        return sanitizedString;
    }
}
