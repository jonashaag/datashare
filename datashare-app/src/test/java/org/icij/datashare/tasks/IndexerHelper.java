package org.icij.datashare.tasks;

import org.apache.commons.io.FilenameUtils;
import org.elasticsearch.client.RestHighLevelClient;
import org.icij.datashare.Entity;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.com.Publisher;
import org.icij.datashare.test.ElasticsearchRule;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.DocumentBuilder;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchIndexer;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchSpewer;
import org.icij.extract.document.DigestIdentifier;
import org.icij.extract.document.DocumentFactory;
import org.icij.extract.document.TikaDocument;
import org.icij.extract.extractor.Extractor;
import org.icij.extract.extractor.UpdatableDigester;
import org.icij.spewer.FieldNames;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static java.nio.file.Paths.get;
import static org.elasticsearch.action.support.WriteRequest.RefreshPolicy.IMMEDIATE;
import static org.icij.datashare.text.Language.ENGLISH;
import static org.mockito.Mockito.mock;

public class IndexerHelper {
    private final RestHighLevelClient client;
    private final ElasticsearchIndexer indexer;

    public IndexerHelper(RestHighLevelClient elasticsearch) {
        this.client = elasticsearch;
        this.indexer = new ElasticsearchIndexer(elasticsearch, new PropertiesProvider()).withRefresh(IMMEDIATE);
    }

    File indexFile(String fileName, String content, TemporaryFolder fs) throws IOException {
        return indexFile(fileName, content, fs, ElasticsearchRule.TEST_INDEX);
    }

    File indexFile(String fileName, String content, TemporaryFolder fs, String indexName) throws IOException {
        String[] pathItems = fileName.split("/");
        File folder = pathItems.length > 1 ? fs.newFolder(Arrays.copyOf(pathItems, pathItems.length - 1)) : fs.getRoot();
        File file = folder.toPath().resolve(pathItems[pathItems.length - 1]).toFile();
        file.createNewFile();
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
        String docname = FilenameUtils.removeExtension(FilenameUtils.getName(fileName));
        Document my_doc = DocumentBuilder.createDoc(docname).with(content).with(file.toPath()).build();
        indexer.add(indexName, my_doc);
        return file;
    }

    File indexEmbeddedFile(String project, String docPath) throws IOException {
        Path path = get(getClass().getResource(docPath).getPath());
        Extractor extractor = new Extractor(new DocumentFactory().withIdentifier(new DigestIdentifier("SHA-384", Charset.defaultCharset())));
        extractor.setDigester(new UpdatableDigester(project, Entity.HASHER.toString()));
        TikaDocument document = extractor.extract(path);
        ElasticsearchSpewer elasticsearchSpewer = new ElasticsearchSpewer(client, l -> ENGLISH,
                new FieldNames(), mock(Publisher.class), new PropertiesProvider()).withRefresh(IMMEDIATE).withIndex("test-datashare");
        elasticsearchSpewer.write(document);
        return path.toFile();
    }
}