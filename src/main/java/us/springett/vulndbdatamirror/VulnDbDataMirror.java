/*
 * This file is part of vulndb-data-mirror.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package us.springett.vulndbdatamirror;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Date;
import java.util.Properties;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import us.springett.vulndbdatamirror.client.VulnDbApi;
import us.springett.vulndbdatamirror.parser.model.Results;
import us.springett.vulndbdatamirror.parser.model.Status;

/**
 * This self-contained class can be called from the command-line. It downloads the
 * contents of the VulnDB service to the specified output path.
 *
 * @author Steve Springett
 * @since 1.0.0
 */
public class VulnDbDataMirror {

    private static final String UPDATE_PROP = "update.properties";

    private final Properties properties = new Properties();
    private File propertyFile;
    private final String consumerKey;
    private final String consumerSecret;
    private final File outputDir;
    private boolean downloadFailed = false;

    public static void main (String[] args) throws Exception {
        final CommandLineParser parser = new DefaultParser();
        final Options options = new Options();

        options.addOption( "stat", "status-only", false, "Displays VulnDB API status only" );
        options.addOption( "vend", "mirror-vendors", false, "Mirror the vendors data feed" );
        options.addOption( "prod", "mirror-products", false, "Mirror the products data feed" );
        options.addOption( "vuln", "mirror-vulnerabilities", false, "Mirror the vulnerabilities data feed" );
        options.addOption("n","no-nvd-additional",false,"Download vulnerabilities without NVD additional information only");

        options.addOption(Option.builder().longOpt("consumer-key").desc("The Consumer Key provided by VulnDB")
                .hasArg().required().argName("key").build()
        );
        options.addOption(Option.builder().longOpt("consumer-secret").desc("The Consumer Secret provided by VulnDB")
                .hasArg().required().argName("secret").build()
        );
        options.addOption(Option.builder().longOpt("dir").desc("The target directory to store contents")
                .hasArg().argName("dir").build()
        );
        options.addOption(Option.builder("u").longOpt("mirror-update").desc("Mirror just the updated vulnerabilities data feed")
                .hasArg().argName("hours").build()
        );

        try {
            final CommandLine line = parser.parse(options, args);
            final VulnDbDataMirror mirror = new VulnDbDataMirror(
                    line.getOptionValue("consumer-key"),
                    line.getOptionValue("consumer-secret"),
                    line.getOptionValue("dir", System.getProperty("user.dir"))
            );

            mirror.getApiStatus();
            if (line.hasOption("status-only")) {
                return;
            }
            if (line.hasOption("mirror-vendors")) {
                mirror.mirrorVendors();
            }
            if (line.hasOption("mirror-products")) {
                mirror.mirrorProducts();
            }
            if (line.hasOption("mirror-vulnerabilities")) {
                if (line.hasOption("no-nvd-additional")) {
                    mirror.mirrorVulnerabilitiesWithFilter();
                } else {
                    mirror.mirrorVulnerabilities();
                }
            }
            if (line.hasOption("mirror-update")) {
                System.out.println("Fetching last updating vulnerabilities");
                final int hours = Integer.parseInt(line.getOptionValue("mirror-update"));
                mirror.mirrorUpdatedVulnerabilities(hours);
            }
            if (!(line.hasOption("mirror-vendors") && line.hasOption("mirror-products") && line.hasOption("mirror-vulnerabilities"))) {
                System.out.println("A feed to mirror was not specified. Defaulting to mirror all feeds.");
                mirror.mirrorVendors();
                mirror.mirrorProducts();
                mirror.mirrorVulnerabilities();
            }
        } catch (ParseException e) {
            final HelpFormatter formatter = new HelpFormatter();
            formatter.printHelp("vulndb-data-mirror", options);
        }
    }

    private VulnDbDataMirror(String consumerKey, String consumerSecret, String outputDirPath) {
        this.consumerKey = consumerKey;
        this.consumerSecret = consumerSecret;
        this.outputDir = new File(outputDirPath);
        if ( ! this.outputDir.exists()) {
            this.outputDir.mkdirs();
        }
        initPropertyFile();
    }

    private void initPropertyFile() {
        this.propertyFile = new File(this.outputDir, UPDATE_PROP);
        if (!this.propertyFile.exists()) {
            try {
                this.propertyFile.createNewFile();
            } catch (IOException e) {
                System.err.println("Unable to create " + UPDATE_PROP + " in " + this.outputDir.getAbsolutePath());
            }
        }
        if (this.propertyFile != null) {
            readProperties();
        }
    }

    private void getApiStatus() throws Exception {
        final VulnDbApi api = new VulnDbApi(this.consumerKey, this.consumerSecret);
        System.out.print("\nVulnDB API Status:\n");
        System.out.println(StringUtils.repeat("-", 80));
        final Status status = api.getStatus();
        System.out.println("Organization Name.............: " + status.getOrganizationName());
        System.out.println("Name of User Requesting.......: " + status.getUserNameRequesting());
        System.out.println("Email of User Requesting......: " + status.getUserEmailRequesting());
        System.out.println("Subscription Expiration Date..: " + status.getSubscriptionEndDate());
        System.out.println("API Calls Allowed per Month...: " + status.getApiCallsAllowedPerMonth());
        System.out.println("API Calls Made This Month.....: " + status.getApiCallsMadeThisMonth());
        System.out.println(StringUtils.repeat("-", 80));
    }

    private void mirrorVendors() throws Exception {
        final VulnDbApi api = new VulnDbApi(this.consumerKey, this.consumerSecret);
        System.out.print("\nMirroring Vendors feed...\n");
        int page = lastSuccessfulPage("vendors");
        boolean more = true;
        while (more) {
            final Results results = api.getVendors(100, page);
            if (results.isSuccessful()) {
                more = processResults(this.outputDir, VulnDbApi.Type.VENDORS, results);
                page++;
            } else {
                System.exit(1);
            }
        }
        if (downloadFailed) {
            System.exit(1);
        }
    }

    private void mirrorProducts() throws Exception {
        final VulnDbApi api = new VulnDbApi(this.consumerKey, this.consumerSecret);
        System.out.print("\nMirroring Products feed...\n");
        int page = lastSuccessfulPage("products");
        boolean more = true;
        while (more) {
            final Results results = api.getProducts(100, page);
            if (results.isSuccessful()) {
                more = processResults(this.outputDir, VulnDbApi.Type.PRODUCTS, results);
                page++;
            } else {
                System.exit(1);
            }
        }
        if (downloadFailed) {
            System.exit(1);
        }
    }

    private void mirrorVulnerabilities() throws Exception {
        final VulnDbApi api = new VulnDbApi(this.consumerKey, this.consumerSecret);
        System.out.print("\nMirroring Vulnerabilities feed...\n");
        int page = lastSuccessfulPage("vulnerabilities");
        boolean more = true;
        while (more) {
            final Results results = api.getVulnerabilities(100, page);
            if (results.isSuccessful()) {
                more = processResults(this.outputDir, VulnDbApi.Type.VULNERABILITIES, results);
                page++;
            } else {
                System.exit(1);
            }
        }
        if (downloadFailed) {
            System.exit(1);
        }
    }
    private void mirrorVulnerabilitiesWithFilter() throws Exception {
        final VulnDbApi api = new VulnDbApi(this.consumerKey, this.consumerSecret);
        System.out.print("\nMirroring Vulnerabilities without NVD Additional Information feed...\n");
        int page = lastSuccessfulPage("vulnerabilities_filtered");
        boolean more = true;
        while (more) {
            final Results results = api.getVulnerabilities(100, page);
            if (results.isSuccessful()) {
                final String filteredResults = filterNVDAdditionalInfo(results.getRawResults());
                if (!filteredResults.isEmpty()) {
                    FileUtils.writeStringToFile(new File(this.outputDir, "vulnerabilities_filtered_" + results.getPage() + ".json"), filteredResults, "UTF-8");
                    storeSuccessfulPage(VulnDbApi.Type.VULNERABILITIES_FILTERED, results.getPage());
                } else {
                    System.out.println("No vulnerabilities without NVD additional information found on page " + page);
                }
                more = results.getPage() * 100 < results.getTotal();
                page++;
            } else {
                System.exit(1);
            }
        }
        if (downloadFailed) {
            System.exit(1);
        }
    }

    private String filterNVDAdditionalInfo(String jsonResults) throws Exception {

        ObjectMapper mapper = new ObjectMapper();
        JsonNode rootNode = mapper.readTree(jsonResults);
        ArrayNode resultsNode = (ArrayNode) rootNode.get("results");
        ArrayNode filteredNode = mapper.createArrayNode();

        System.out.print("\nFILTERING...\n");
        for (JsonNode result : resultsNode) {
            JsonNode nvdAdditionalInfo = result.get("nvd_additional_information");
            System.out.print(nvdAdditionalInfo);
            System.out.print("\n\n");
            if (nvdAdditionalInfo != null && nvdAdditionalInfo.isArray() && nvdAdditionalInfo.size() == 0) {
                filteredNode.add(result);
            }
        }

        ObjectNode filteredResultsNode = mapper.createObjectNode();
        filteredResultsNode.set("results", filteredNode);
        filteredResultsNode.put("total_entries", rootNode.get("total_entries").asInt());
        filteredResultsNode.put("current_page", rootNode.get("current_page").asInt());

        return mapper.writeValueAsString(filteredResultsNode);
    }

    private void mirrorUpdatedVulnerabilities(final int hours_ago) throws Exception {
        final VulnDbApi api = new VulnDbApi(this.consumerKey, this.consumerSecret);
        System.out.print("\nMirroring Updated Vulnerabilities feed...\n");
        int page = lastSuccessfulPage("vulnerabilities");
        boolean more = true;
        while (more) {
            final Results results = api.getUpdatedVulnerabilities(100, page,hours_ago);
            if (results.isSuccessful()) {
                more = processResults(this.outputDir, VulnDbApi.Type.VULNERABILITIES, results);
                page++;
            } else {
                System.exit(1);
            }
        }
        if (downloadFailed) {
            System.exit(1);
        }
    }

    private int lastSuccessfulPage(String prefix) {
        if (prefix.equals("vulnerabilities_filtered")) {
            prefix = "vulnerabilities_filtered"; // Adjust property key as needed
        }
        return Integer.parseInt(properties.getProperty(prefix + ".last_success_page", "1"));
    }

    private void storeSuccessfulPage(VulnDbApi.Type type, int page) {
        String prefix = type.name().toLowerCase();
        if (type == VulnDbApi.Type.VULNERABILITIES_FILTERED) { // Assuming you'll add this to your VulnDbApi.Type enum
            prefix = "vulnerabilities_filtered";
        }
        properties.setProperty(prefix + ".last_success_page", String.valueOf(page));
        properties.setProperty(prefix + ".last_success_timestamp", String.valueOf(new Date().getTime()));
        writeProperties();
    }

    private boolean processResults(File dir, VulnDbApi.Type type, Results results) throws Exception {
        try {
            FileUtils.writeStringToFile(new File(dir, type.name().toLowerCase() + "_" + results.getPage() + ".json"), results.getRawResults(), "UTF-8");
            storeSuccessfulPage(type, results.getPage());
        } catch (IOException ex) {
            System.err.println("Error writing VulnDB output to file");
            System.err.println(ex.getMessage());
        }
        logProgress(results.getPage() * 100, results.getTotal());
        return results.getPage() * 100 < results.getTotal();
    }

    private void logProgress(int currentResults, int totalResults) throws Exception {
        if (currentResults > totalResults) {
            currentResults = totalResults;
        }
        final String data = "\rProcessing " + currentResults + " of " + totalResults + " results";
        System.out.write(data.getBytes());
    }

    private void readProperties() {
        try (InputStream in = new FileInputStream(this.propertyFile)) {
            properties.load(in);
        } catch (IOException  e) {
            System.err.println("Unable to read " + UPDATE_PROP);
            System.err.println(e.getMessage());
        }
    }

    private void writeProperties() {
        try (OutputStream os = new FileOutputStream(this.propertyFile)) {
            properties.store(os, "Automatically generated from vulndb-data-mirror. Do not modify.");
        } catch (IOException  e) {
            System.err.println("Unable to write to " + UPDATE_PROP);
            System.err.println(e.getMessage());
        }
    }

}
