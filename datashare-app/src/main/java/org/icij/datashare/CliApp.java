package org.icij.datashare;

import com.google.inject.Injector;
import org.apache.commons.compress.archivers.ArchiveException;
import org.icij.datashare.cli.DatashareCli;
import org.icij.datashare.cli.DatashareCliOptions;
import org.icij.datashare.extension.PipelineRegistry;
import org.icij.datashare.extract.RedisUserDocumentQueue;
import org.icij.datashare.mode.CommonMode;
import org.icij.datashare.tasks.TaskFactory;
import org.icij.datashare.tasks.TaskManager;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.nlp.Pipeline;
import org.icij.extract.queue.DocumentQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.Set;

import static com.google.inject.Guice.createInjector;
import static java.lang.Boolean.parseBoolean;
import static java.util.Optional.ofNullable;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.icij.datashare.PropertiesProvider.MAP_NAME_OPTION;
import static org.icij.datashare.cli.DatashareCliOptions.*;
import static org.icij.datashare.text.nlp.Pipeline.Type.parseAll;
import static org.icij.datashare.user.User.localUser;
import static org.icij.datashare.user.User.nullUser;

class CliApp {
    private static final Logger logger = LoggerFactory.getLogger(CliApp.class);

    static void start(Properties properties) throws Exception {
        displayInfoWithoutIoc(properties);
        Injector injector = createInjector(CommonMode.create(properties));
        runTaskRunner(injector, properties);
    }

    private static void displayInfoWithoutIoc(Properties properties) throws IOException, ArchiveException {
        if (properties.getProperty(PLUGIN_LIST_OPT) != null) {
            new PluginService().list(properties.getProperty(PLUGIN_LIST_OPT)).forEach(p -> {
                System.out.println("plugin " + p.id);
                System.out.println("\t" + p.name);
                System.out.println("\t" + p.version);
                System.out.println("\t" + p.url);
                System.out.println("\t" + p.description);
            });
            System.exit(0);
        }
        String pluginIdOrUrlOrFile = properties.getProperty(PLUGIN_INSTALL_OPT);
        if (pluginIdOrUrlOrFile != null) {
            PluginService pluginService = new PluginService(new PropertiesProvider(properties));
            try {
                pluginService.downloadAndInstall(pluginIdOrUrlOrFile); // plugin with id
            } catch (PluginRegistry.UnknownPluginException not_a_plugin) {
                try {
                   URL pluginUrl = new URL(pluginIdOrUrlOrFile);
                   pluginService.downloadAndInstall(pluginUrl); // from url
                } catch (MalformedURLException not_url) {
                    pluginService.install(Paths.get(pluginIdOrUrlOrFile).toFile()); // from file
                }
            }
            System.exit(0);
        }
    }

    private static void runTaskRunner(Injector injector, Properties properties) throws Exception {
        TaskManager taskManager = injector.getInstance(TaskManager.class);
        TaskFactory taskFactory = injector.getInstance(TaskFactory.class);

        Set<Pipeline.Type> nlpPipelines = parseAll(properties.getProperty(DatashareCliOptions.NLP_PIPELINES_OPT));
        Indexer indexer = injector.getInstance(Indexer.class);

        if (resume(properties)) {
            RedisUserDocumentQueue queue = new RedisUserDocumentQueue(nullUser(), new PropertiesProvider(properties));
            boolean queueIsEmpty = queue.isEmpty();
            queue.close();

            if (indexer.search(properties.getProperty("defaultProject"), Document.class).withSource(false).without(nlpPipelines.toArray(new Pipeline.Type[]{})).execute().count() == 0 && queueIsEmpty) {
                logger.info("nothing to resume, exiting normally");
                System.exit(0);
            }
        }

        if (properties.getProperty(CRE_API_KEY_OPT) != null) {
            String userName = properties.getProperty(CRE_API_KEY_OPT);
            String secretKey = taskFactory.createGenApiKey(localUser(userName)).call();
            logger.info("generated secret key for user {} (store it somewhere safe, datashare cannot retrieve it later): {}", userName, secretKey);
            System.exit(0);
        }

        if (properties.getProperty(GET_API_KEY_OPT) != null) {
            String userName = properties.getProperty(GET_API_KEY_OPT);
            String hashedKey = taskFactory.createGetApiKey(localUser(userName)).call();
            if ((hashedKey == null)) {
                logger.info("no user {} exists", userName);
            } else {
                logger.info("hashed key for user {} is {}", userName, hashedKey);
            }
            System.exit(0);
        }

        if (properties.getProperty(DEL_API_KEY_OPT) != null) {
            String userName = properties.getProperty(DEL_API_KEY_OPT);
            taskFactory.createDelApiKey(localUser(userName)).call();
            System.exit(0);
        }

        PipelineHelper pipeline = new PipelineHelper(new PropertiesProvider(properties));
        if (pipeline.has(DatashareCli.Stage.DEDUPLICATE)) {
            taskManager.startTask(taskFactory.createDeduplicateTask(nullUser(), pipeline.getQueueNameFor(DatashareCli.Stage.DEDUPLICATE)));
        }

        if (pipeline.has(DatashareCli.Stage.SCANIDX)) {
            TaskManager.MonitorableFutureTask<Long> longMonitorableFutureTask = taskManager.startTask(taskFactory.createScanIndexTask(nullUser(), ofNullable(properties.getProperty(MAP_NAME_OPTION)).orElse("extract:report")));
            logger.info("scanned {}", longMonitorableFutureTask.get());
        }

        if (pipeline.has(DatashareCli.Stage.SCAN) && !resume(properties)) {
            taskManager.startTask(taskFactory.createScanTask(nullUser(), pipeline.getQueueNameFor(DatashareCli.Stage.SCAN), Paths.get(properties.getProperty(DatashareCliOptions.DATA_DIR_OPT)), properties),
                    () -> closeAndLogException(injector.getInstance(DocumentQueue.class)).run());
        }

        if (pipeline.has(DatashareCli.Stage.INDEX)) {
            taskManager.startTask(taskFactory.createIndexTask(nullUser(), pipeline.getQueueNameFor(DatashareCli.Stage.INDEX), properties),
                    () -> closeAndLogException(injector.getInstance(DocumentQueue.class)).run());
        }

        if (pipeline.has(DatashareCli.Stage.NLP)) {
            for (Pipeline.Type nlp : nlpPipelines) {
                Pipeline pipelineClass = injector.getInstance(PipelineRegistry.class).get(nlp);
                taskManager.startTask(taskFactory.createNlpTask(nullUser(), pipelineClass));
            }
            if (resume(properties)) {
                taskManager.startTask(taskFactory.createResumeNlpTask(nullUser(), nlpPipelines));
            }
        }
        taskManager.shutdownAndAwaitTermination(Integer.MAX_VALUE, SECONDS);
        indexer.close();
    }

    private static Runnable closeAndLogException(AutoCloseable closeable) {
        return () -> {
            try {
                closeable.close();
            } catch (Exception e) {
                logger.error("error while closing", e);
            }
        };
    }

    private static boolean resume(Properties properties) {
        return parseBoolean(properties.getProperty(DatashareCliOptions.RESUME_OPT, "false"));
    }
}
