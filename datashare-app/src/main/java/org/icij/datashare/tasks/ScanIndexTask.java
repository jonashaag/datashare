package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import org.icij.datashare.Entity;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.user.User;
import org.icij.datashare.user.UserTask;
import org.icij.extract.extractor.ExtractionStatus;
import org.icij.extract.report.Report;
import org.icij.extract.report.ReportMap;
import org.icij.task.DefaultTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static java.lang.Integer.parseInt;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static org.icij.datashare.cli.DatashareCliOptions.SCROLL_SIZE;

public class ScanIndexTask extends DefaultTask<Long> implements UserTask {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final Indexer indexer;
    private final int scrollSize;
    private final String projectName;
    private final ReportMap reportMap;
    private final User user;
    private final int scrollSlices;

    @Inject
    public ScanIndexTask(DocumentCollectionFactory factory, final Indexer indexer, final PropertiesProvider propertiesProvider,
                         @Assisted User user, @Assisted String reportName) {
        this.user = user;
        this.scrollSize = parseInt(propertiesProvider.get(SCROLL_SIZE).orElse("1000"));
        this.scrollSlices = parseInt(propertiesProvider.get("scrollSlices").orElse("1"));
        this.projectName = propertiesProvider.get("defaultProject").orElse("local-datashare");
        this.reportMap = factory.createMap(propertiesProvider, reportName);
        this.indexer = indexer;
    }

    @Override
    public Long call() throws Exception {
        logger.info("scanning index {} with scroll size {} and {} slices", projectName, scrollSize, scrollSlices);
        Optional<Long> nb = IntStream.range(0, scrollSlices).parallel().mapToObj(this::slicedScroll).reduce(Long::sum);
        logger.info("imported {} paths into {}", nb.get(), reportMap);
        reportMap.close();
        return nb.get();
    }

    private Long slicedScroll(int sliceNum) {
        Indexer.Searcher search = indexer.search(singletonList(projectName), Document.class).withSource("path").limit(scrollSize);
        List<? extends Entity> docsToProcess = new ArrayList<>();
        long nbProcessed = 0;
        do {
            try {
                docsToProcess = search.scroll(sliceNum, scrollSlices).collect(toList());
                reportMap.putAll(docsToProcess.stream().map(d -> ((Document) d).getPath()).collect(toMap(p -> p, p -> new Report(ExtractionStatus.SUCCESS), (a, b) -> b)));
                nbProcessed += docsToProcess.size();
            } catch (IOException e) {
                logger.error("error in slice {}", sliceNum, e);
            }
        } while (docsToProcess.size() != 0);
        return nbProcessed;
    }

    @Override
    public User getUser() {
        return user;
    }
}
