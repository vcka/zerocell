package com.creditdatamw.zerocell.processor.spec;

import com.creditdatamw.zerocell.ZeroCellException;
import com.creditdatamw.zerocell.ZeroCellReader;
import com.creditdatamw.zerocell.annotation.RowNumber;
import com.creditdatamw.zerocell.processor.ZeroCellAnnotationProcessor;
import com.squareup.javapoet.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.type.TypeMirror;
import java.util.*;
import java.util.regex.Pattern;

import static com.creditdatamw.zerocell.processor.spec.CellMethodSpec.beanSetterPropertyName;
import static com.creditdatamw.zerocell.processor.spec.ColumnInfoType.columnsOf;

public class ReaderTypeSpec {
    private final TypeElement typeElement;
    private final String readerClassName;

    private static final String INVALID_CHARS_REGEX = "\\s";

    public ReaderTypeSpec(TypeElement typeElement, Optional<String> customReaderName) {
        Objects.requireNonNull(typeElement);
        this.typeElement = typeElement;
        this.readerClassName = customReaderName.orElse(
                String.format("%sReader", typeElement.getSimpleName()));
    }

    public JavaFile build() throws java.io.IOException {
        assertReaderName();
        LoggerFactory.getLogger(ZeroCellAnnotationProcessor.class)
                .info("Processing class: {}", typeElement);
        ClassName dataClass = ClassName.get(typeElement);
        ClassName list = ClassName.get("java.util", "List");
        ClassName readerUtil = ClassName.get("com.creditdatamw.zerocell", "ReaderUtil");
        ClassName arrayList = ClassName.get("java.util", "ArrayList");
        ClassName convertersClass = ClassName.get("com.creditdatamw.zerocell.converter", "Converters");

        TypeName listOfData = ParameterizedTypeName.get(list, dataClass);
        TypeName zeroCellReader = ParameterizedTypeName.get(ClassName.get(ZeroCellReader.class), dataClass);

        MethodSpec reset = MethodSpec.methodBuilder("reset")
                .addModifiers(Modifier.PRIVATE)
                .addStatement("this.currentRow = -1")
                .addStatement("this.currentCol = -1")
                .addStatement("this.cur = null")
                .addStatement("this.data.clear()")
                .build();

        MethodSpec read = MethodSpec.methodBuilder("read")
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class)
                .addParameter(java.io.File.class, "file", Modifier.FINAL)
                .addParameter(String.class, "sheet", Modifier.FINAL)
                .returns(listOfData)
                .addStatement("this.reset()")
                .addStatement("$T.process(file, sheet, this)", readerUtil)
                .addStatement("List<$T> dataList", typeElement)
                .addStatement("dataList = $T.unmodifiableList(this.data)", Collections.class)
                .addStatement("return dataList")
                .build();

        MethodSpec.Builder startRowBuilder = MethodSpec.methodBuilder("startRow")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .addParameter(Integer.TYPE, "i", Modifier.FINAL)
                .addStatement("currentRow = i")
                .addStatement("isHeaderRow = false")
                .addComment("Skip header row")
                .beginControlFlow("if (currentRow == 0)")
                .addStatement("isHeaderRow=true")
                .addStatement("return")
                .endControlFlow()
                .addStatement("cur = new $T()", dataClass);

        checkRowNumberField().ifPresent(fieldName -> {
            //we'll get something like startRowBuilder.addStatement("cur.setRowNumber(currentRow)");
            startRowBuilder.addStatement("cur.set$L(currentRow)", fieldName);
        });

        MethodSpec startRow = startRowBuilder.build();

        MethodSpec endRow = MethodSpec.methodBuilder("endRow")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .addParameter(Integer.TYPE, "i", Modifier.FINAL)
                .beginControlFlow("if (! java.util.Objects.isNull(cur))")
                .addStatement("this.data.add(cur)")
                .addStatement("this.cur = null")
                .endControlFlow()
                .build();

        MethodSpec headerFooter = MethodSpec.methodBuilder("headerFooter")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .addParameter(String.class, "text", Modifier.FINAL)
                .addParameter(boolean.class, "b", Modifier.FINAL)
                .addParameter(String.class, "tagName", Modifier.FINAL)
                .addComment("Skip, not processing headers or footers here")
                .build();

        // fields to spec
        List<FieldSpec> columnIndexFields = new ArrayList<>();

        List<ColumnInfoType> columnInfoList = columnsOf(typeElement);

        columnInfoList.forEach(column -> {
            int idx= column.getIndex();
            String name = column.getName();
            String normalizedName = String.format("COL_%s", column.getIndex()).toUpperCase();
            columnIndexFields.add(FieldSpec.builder(
                    Integer.TYPE,
                    normalizedName,
                    Modifier.STATIC, Modifier.FINAL)
                    .initializer("$L", idx)
                    .build());
        });

        MethodSpec constructor = MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addStatement("this.data = new $T<>()", arrayList)
                .build();

        MethodSpec cell = CellMethodSpec.build(columnInfoList);

        MethodSpec assertColumnName = MethodSpec.methodBuilder("assertColumnName")
                .addParameter(String.class, "columnName", Modifier.FINAL)
                .addParameter(String.class, "value", Modifier.FINAL)
                .beginControlFlow("if (validateHeaders && isHeaderRow)")
                .beginControlFlow("if (! columnName.equalsIgnoreCase(value))")
                .addStatement("throw new $T(String.format($S, columnName, value))", ZeroCellException.class, "Expected Column '%s' but found '%s'")
                .endControlFlow()
                .endControlFlow()
                .build();

        TypeSpec readerTypeSpec = TypeSpec.classBuilder(readerClassName)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addSuperinterface(zeroCellReader)
                .addField(FieldSpec.builder(Logger.class, "LOGGER", Modifier.PRIVATE, Modifier.STATIC)
                        .initializer("$T.getLogger($L.class)", LoggerFactory.class, typeElement.getQualifiedName())
                        .build())
                .addFields(columnIndexFields)
                .addField(FieldSpec.builder(Boolean.TYPE, "validateHeaders", Modifier.PRIVATE).build())
                .addField(FieldSpec.builder(Boolean.TYPE, "isHeaderRow", Modifier.PRIVATE).build())
                .addField(FieldSpec.builder(Integer.TYPE, "currentRow", Modifier.PRIVATE).build())
                .addField(FieldSpec.builder(Integer.TYPE, "currentCol", Modifier.PRIVATE).build())
                .addField(FieldSpec.builder(dataClass, "cur", Modifier.PRIVATE).build())
                .addField(FieldSpec.builder(listOfData, "data", Modifier.PRIVATE).build())
                .addMethod(read)
                .addMethod(reset)
                .addMethod(constructor)
                .addMethod(headerFooter)
                .addMethod(startRow)
                .addMethod(cell)
                .addMethod(endRow)
                .addMethod(assertColumnName)
                .build();

        final JavaFile javaFile = JavaFile.builder(dataClass.packageName(), readerTypeSpec )
                .addStaticImport(convertersClass, "*")
                .build();
        LoggerFactory.getLogger(ZeroCellAnnotationProcessor.class)
                .info("Generated reader class: {}", readerTypeSpec.name);
        return javaFile;
    }

    private void assertReaderName() {
        if (! Pattern.matches("[A-Za-z]+\\d*[A-Za-z]", readerClassName)) {
            throw new IllegalArgumentException("Invalid name for the reader Class: " + readerClassName);
        }
    }

    private Optional<String> checkRowNumberField() {
        for(Element element: typeElement.getEnclosedElements()) {
            if (! element.getKind().isField()) {
                continue;
            }
            RowNumber annotation = element.getAnnotation(RowNumber.class);
            if (! Objects.isNull(annotation)) {
                TypeMirror type;
                try {
                    type = element.asType();
                } catch (MirroredTypeException mte) {
                    type = mte.getTypeMirror();
                }
                final String fieldType = String.format("%s", type);
                final String fieldName = element.getSimpleName().toString();
                if (fieldType.equals(int.class.getTypeName())     ||
                    fieldType.equals(long.class.getTypeName())    ||
                    fieldType.equals(Integer.class.getTypeName()) ||
                    fieldType.equals(Long.class.getTypeName())
                ) {
                    return Optional.of(beanSetterPropertyName(fieldName));
                } else {
                    // Must be one of the integer classes or bust!
                    throw new IllegalArgumentException(
                            String.format("Invalid type (%s) for @RowNumber field (%s). Only java.lang.Integer and java.lang.Long are allowed", fieldType, fieldName));
                }
            }
        }
        return Optional.empty();
    }
}
