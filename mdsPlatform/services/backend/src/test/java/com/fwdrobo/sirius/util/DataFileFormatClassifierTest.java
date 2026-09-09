package com.fwdrobo.sirius.util;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DataFileFormatClassifierTest {
    @ParameterizedTest
    @CsvSource({
            "photo.JPG,image,JPG,IMAGE",
            "photo.png,image,PNG,IMAGE",
            "drawing.svg,image,SVG,IMAGE",
            "report.txt,text,TXT,TEXT",
            "records.csv,csv,CSV,CSV",
            "metadata.json,json,JSON,JSON",
            "events.jsonl,jsonl,JSONL,JSONL",
            "ledger.xlsx,xlsx,XLSX,XLSX",
            "manual.pdf,pdf,PDF,PDF",
            "model.obj,obj,OBJ,OBJ",
            "assembly.STEP,step,STEP,STEP",
            "id_map.parquet,parquet,PARQUET,PARQUET",
            "faiss.index,faiss,FAISS,FAISS"
    })
    void classifiesFormatsPresentInTheDataSpace(String fileName,
                                                 String storageCategory,
                                                 String format,
                                                 String mediaType) {
        DataFileFormatClassifier.Classification actual =
                DataFileFormatClassifier.classify(fileName, "application/octet-stream");

        assertEquals(storageCategory, actual.storageCategory());
        assertEquals(format, actual.format());
        assertEquals(mediaType, actual.mediaType());
    }

    @ParameterizedTest
    @CsvSource({
            "table.csv,text/csv",
            "records.jsonl,application/x-ndjson",
            "workbook.xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "manual.pdf,application/pdf",
            "mesh.obj,model/obj",
            "assembly.step,model/step",
            "map.parquet,application/vnd.apache.parquet"
    })
    void resolvesGenericUploadContentTypes(String fileName, String expected) {
        assertEquals(expected,
                DataFileFormatClassifier.resolveContentType(fileName, "application/octet-stream"));
    }
}
