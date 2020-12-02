package net.christophschubert.kafka.clusterstate.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import net.christophschubert.kafka.clusterstate.ClusterState;
import net.christophschubert.kafka.clusterstate.formats.env.CloudCluster;
import net.christophschubert.kafka.clusterstate.formats.env.Environment;
import net.christophschubert.kafka.clusterstate.utils.MapTools;
import org.apache.commons.text.StringSubstitutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.stream.Collectors;

@CommandLine.Command(name= "cloudgovernance", subcommands = { CommandLine.HelpCommand.class }, version = "0.1.0",
        description = "Manage metadata of Confluent Cloud clusters.")
public class CloudGovernance {

    private final Logger logger = LoggerFactory.getLogger(CLI.class);

    @CommandLine.Command(name = "apply", description = "Apply domain description from context to a cluster")
    int apply(
            @CommandLine.Parameters(paramLabel = "environment", description = "path to the environment", defaultValue = ".") File contextPath
    ) {
        final ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        try {
            final var environment = mapper.readValue(contextPath, Environment.class);
            environment.clusters.forEach(c -> applyCluster(c, System.getenv()));
        } catch (IOException e) {
            e.printStackTrace();
            return 1;
        }
        return 0;
    }

    Map<String, String> extractEnvVars(String clusterName, Map<String, String> envVars) {
        final String kstEnvVarPrefix = "KST";
        final var varKeys = List.of("CLUSTER_API_KEY", "CLUSTER_API_SECRET", "SR_API_KEY", "SR_API_SECRET");

        final Map<String, String> res = new HashMap<>();
        varKeys.forEach(key -> {
            final var envVarName = kstEnvVarPrefix + "_" + key + "_" + clusterName;
            res.put(key, envVars.get(envVarName));
        });
        return res;
    }


    /**
     * The following entries from the envVars-map will used for substitutions:
     *  KST_CLUSTER_API_SECRET_<clusterName>
     *  KST_CLUSTER_API_KEY_<clusterName>
     *  KST_SR_API_SECRET_<clusterName>
     *  KST_SR_API_KEY_<clusterName>
     *
     * @param cluster
     * @param envVars
     */
    void applyCluster(CloudCluster cluster, Map<String, String> envVars) {

        final var substitutions = extractEnvVars(cluster.name, envVars);


        /*
        env vars to create to
         export KST_CLUSTER_API_SECRET_cluster_AO_QA=Q-api-secret
         export KST_CLUSTER_API_KEY_cluster_AO_QA=Q-api-key
         export KST_SR_API_SECRET_cluster_AO_QA=Q-sr-secret
         export KST_SR_API_KEY_cluster_AO_QA=Q-sr-key
         */

        final StringSubstitutor substitutor = new StringSubstitutor(substitutions);


        final var clientProps = new Properties();
        clientProps.putAll(MapTools.mapValues(CLITools.getClientProps(cluster), substitutor::replace));

        //build principal mapping
        final List<Function<ClusterState, ClusterState>> stateTransforms = Collections.singletonList(cs -> cs.mapPrincipals(cluster.principals));

        try {
            //todo: fix this
            final var folders = cluster.domainFileFolders.stream().map(File::new).collect(Collectors.toList());
            final Runner runner = new Runner(folders, new File(cluster.clusterLevelAccessPath
            ), clientProps, stateTransforms);
            runner.run();
        } catch (IOException e) {
            e.printStackTrace();
            System.exit(1);
        } catch (ExecutionException e) {
            e.printStackTrace();
            System.exit(1);
        } catch (InterruptedException e) {
            e.printStackTrace();
            System.exit(1);
        }
    }


    public static void main(String... args) {
        final int status = new CommandLine(new CloudGovernance()).execute(args);
        System.exit(status);
    }

}
