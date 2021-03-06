// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.config.server.http.v2;

import com.google.inject.Inject;
import com.yahoo.component.Version;
import com.yahoo.config.application.api.ApplicationFile;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.ApplicationName;
import com.yahoo.config.provision.HostFilter;
import com.yahoo.config.provision.TenantName;
import com.yahoo.config.provision.Zone;
import com.yahoo.container.jdisc.HttpRequest;
import com.yahoo.container.jdisc.HttpResponse;
import com.yahoo.io.IOUtils;
import com.yahoo.jdisc.Response;
import com.yahoo.jdisc.application.BindingMatch;
import com.yahoo.jdisc.application.UriPattern;
import com.yahoo.slime.Cursor;
import com.yahoo.vespa.config.server.ApplicationRepository;
import com.yahoo.vespa.config.server.application.ApplicationReindexing;
import com.yahoo.vespa.config.server.http.ContentHandler;
import com.yahoo.vespa.config.server.http.ContentRequest;
import com.yahoo.vespa.config.server.http.HttpErrorResponse;
import com.yahoo.vespa.config.server.http.HttpHandler;
import com.yahoo.vespa.config.server.http.JSONResponse;
import com.yahoo.vespa.config.server.http.NotFoundException;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import static java.util.Map.Entry.comparingByKey;
import static java.util.stream.Collectors.toList;

/**
 * Operations on applications (delete, wait for config convergence, restart, application content etc.)
 *
 * @author hmusum
 */
public class ApplicationHandler extends HttpHandler {

    private static final List<UriPattern> URI_PATTERNS = Stream.of(
            "http://*/application/v2/tenant/*/application/*/environment/*/region/*/instance/*/content/*",
            "http://*/application/v2/tenant/*/application/*/environment/*/region/*/instance/*/filedistributionstatus",
            "http://*/application/v2/tenant/*/application/*/environment/*/region/*/instance/*/restart",
            "http://*/application/v2/tenant/*/application/*/environment/*/region/*/instance/*/reindex",
            "http://*/application/v2/tenant/*/application/*/environment/*/region/*/instance/*/reindexing",
            "http://*/application/v2/tenant/*/application/*/environment/*/region/*/instance/*/suspended",
            "http://*/application/v2/tenant/*/application/*/environment/*/region/*/instance/*/serviceconverge",
            "http://*/application/v2/tenant/*/application/*/environment/*/region/*/instance/*/serviceconverge/*",
            "http://*/application/v2/tenant/*/application/*/environment/*/region/*/instance/*/clustercontroller/*/status/*",
            "http://*/application/v2/tenant/*/application/*/environment/*/region/*/instance/*/metrics/*",
            "http://*/application/v2/tenant/*/application/*/environment/*/region/*/instance/*/metrics/*",
            "http://*/application/v2/tenant/*/application/*/environment/*/region/*/instance/*/logs",
            "http://*/application/v2/tenant/*/application/*/environment/*/region/*/instance/*/tester/*/*",
            "http://*/application/v2/tenant/*/application/*/environment/*/region/*/instance/*/tester/*",
            "http://*/application/v2/tenant/*/application/*/environment/*/region/*/instance/*/quota",
            "http://*/application/v2/tenant/*/application/*/environment/*/region/*/instance/*",
            "http://*/application/v2/tenant/*/application/*")
            .map(UriPattern::new)
            .collect(toList());

    private final Zone zone;
    private final ApplicationRepository applicationRepository;

    @Inject
    public ApplicationHandler(HttpHandler.Context ctx,
                              Zone zone,
                              ApplicationRepository applicationRepository) {
        super(ctx);
        this.zone = zone;
        this.applicationRepository = applicationRepository;
    }

    @Override
    public HttpResponse handleDELETE(HttpRequest request) {
        ApplicationId applicationId = getApplicationIdFromRequest(request);

        if (isReindexingRequest(request)) {
            applicationRepository.modifyReindexing(applicationId, reindexing -> reindexing.enabled(false));
            return new JSONResponse(Response.Status.OK);
        }

        if (applicationRepository.delete(applicationId))
            return new DeleteApplicationResponse(Response.Status.OK, applicationId);

        return HttpErrorResponse.notFoundError("Unable to delete " + applicationId.toFullString() + ": Not found");
    }

    @Override
    public HttpResponse handleGET(HttpRequest request) {
        ApplicationId applicationId = getApplicationIdFromRequest(request);
        Duration timeout = HttpHandler.getRequestTimeout(request, Duration.ofSeconds(5));

        if (isServiceConvergeRequest(request)) {
            // Expects both hostname and port in the request (hostname:port)
            String hostAndPort = getHostNameFromRequest(request);
            return applicationRepository.checkServiceForConfigConvergence(applicationId, hostAndPort, request.getUri(),
                                                                          timeout, getVespaVersionFromRequest(request));
        }

        if (isClusterControllerStatusRequest(request)) {
            String hostName = getHostNameFromRequest(request);
            String pathSuffix = getPathSuffix(request);
            return applicationRepository.clusterControllerStatusPage(applicationId, hostName, pathSuffix);
        }

        if (isReindexingRequest(request)) {
            return getReindexingStatus(applicationId);
        }

        if (isContentRequest(request)) {
            long sessionId = applicationRepository.getSessionIdForApplication(applicationId);
            String contentPath = getBindingMatch(request).group(7);
            ApplicationFile applicationFile =
                    applicationRepository.getApplicationFileFromSession(applicationId.tenant(),
                                                                        sessionId,
                                                                        contentPath,
                                                                        ContentRequest.getApplicationFileMode(request.getMethod()));
            ApplicationContentRequest contentRequest = new ApplicationContentRequest(request,
                                                                                     sessionId,
                                                                                     applicationId,
                                                                                     zone,
                                                                                     contentPath,
                                                                                     applicationFile);
            return new ContentHandler().get(contentRequest);
        }

        if (isServiceConvergeListRequest(request)) {
            return applicationRepository.servicesToCheckForConfigConvergence(applicationId, request.getUri(), timeout,
                                                                             getVespaVersionFromRequest(request));
        }

        if (isFiledistributionStatusRequest(request)) {
            return applicationRepository.filedistributionStatus(applicationId, timeout);
        }

        if (isLogRequest(request)) {
            Optional<String> hostname = Optional.ofNullable(request.getProperty("hostname"));
            String apiParams = Optional.ofNullable(request.getUri().getQuery()).map(q -> "?" + q).orElse("");
            return applicationRepository.getLogs(applicationId, hostname, apiParams);
        }

        if (isProtonMetricsRequest(request)) {
            return applicationRepository.getProtonMetrics(applicationId);
        }

        if (isDeploymentMetricsRequest(request)) {
            return applicationRepository.getDeploymentMetrics(applicationId);
        }

        if (isIsSuspendedRequest(request)) {
            return new ApplicationSuspendedResponse(applicationRepository.isSuspended(applicationId));
        }

        if (isTesterRequest(request)) {
            String testerCommand = getTesterCommandFromRequest(request);
            switch (testerCommand) {
                case "status":
                    return applicationRepository.getTesterStatus(applicationId);
                case "log":
                    Long after = Long.valueOf(request.getProperty("after"));
                    return applicationRepository.getTesterLog(applicationId, after);
                case "ready":
                    return applicationRepository.isTesterReady(applicationId);
                case "report":
                    return applicationRepository.getTestReport(applicationId);
                default:
                    throw new IllegalArgumentException("Unknown tester command in request " + request.getUri().toString());
            }
        }

        if (isQuotaUsageRequest(request)) {
            var quotaUsageRate = applicationRepository.getQuotaUsageRate(applicationId);
            return new QuotaUsageResponse(quotaUsageRate);
        }

        return getApplicationResponse(applicationId);
    }

    GetApplicationResponse getApplicationResponse(ApplicationId applicationId) {
        return new GetApplicationResponse(Response.Status.OK,
                                          applicationRepository.getApplicationGeneration(applicationId),
                                          applicationRepository.getAllVersions(applicationId),
                                          applicationRepository.getApplicationPackageReference(applicationId));
    }

    @Override
    public HttpResponse handlePOST(HttpRequest request) {
        ApplicationId applicationId = getApplicationIdFromRequest(request);

        if (isRestartRequest(request))
            return restart(request, applicationId);

        if (isTesterStartTestsRequest(request)) {
            byte[] data;
            try {
                data = IOUtils.readBytes(request.getData(), 1024 * 1000);
            } catch (IOException e) {
                throw new IllegalArgumentException("Could not read data in request " + request);
            }
            return applicationRepository.startTests(applicationId, getSuiteFromRequest(request), data);
        }

        if (isReindexRequest(request)) {
            triggerReindexing(request, applicationId);
            return new JSONResponse(Response.Status.OK);
        }

        if (isReindexingRequest(request)) {
            applicationRepository.modifyReindexing(applicationId, reindexing -> reindexing.enabled(true));
            return new JSONResponse(Response.Status.OK);
        }

        throw new NotFoundException("Illegal POST request '" + request.getUri() + "'");
    }

    private void triggerReindexing(HttpRequest request, ApplicationId applicationId) {
        List<String> clusters = Optional.ofNullable(request.getProperty("cluster")).stream()
                                        .flatMap(value -> Stream.of(value.split(",")))
                                        .filter(cluster -> ! cluster.isBlank())
                                        .collect(toList());
        List<String> types = Optional.ofNullable(request.getProperty("type")).stream()
                                     .flatMap(value -> Stream.of(value.split(",")))
                                     .filter(type -> ! type.isBlank())
                                     .collect(toList());
        Instant now = applicationRepository.clock().instant();
        applicationRepository.modifyReindexing(applicationId, reindexing -> {
            if (clusters.isEmpty())
                reindexing = reindexing.withReady(now);
            else
                for (String cluster : clusters)
                    if (types.isEmpty())
                        reindexing = reindexing.withReady(cluster, now);
                    else
                        for (String type : types)
                            reindexing = reindexing.withReady(cluster, type, now);
            return reindexing;
        });
    }

    private HttpResponse getReindexingStatus(ApplicationId applicationId) {
        return new ReindexResponse(applicationRepository.getTenant(applicationId).getApplicationRepo().database()
                                                        .readReindexingStatus(applicationId)
                                                        .orElseThrow(() -> new NotFoundException("Reindexing status not found for " + applicationId)));
    }

    private HttpResponse restart(HttpRequest request, ApplicationId applicationId) {
        if (getBindingMatch(request).groupCount() != 7)
            throw new NotFoundException("Illegal POST restart request '" + request.getUri() +
                                        "': Must have 6 arguments but had " + (getBindingMatch(request).groupCount() - 1));
        applicationRepository.restart(applicationId, hostFilterFrom(request));
        return new JSONResponse(Response.Status.OK); // return empty
    }

    private HostFilter hostFilterFrom(HttpRequest request) {
        return HostFilter.from(request.getProperty("hostname"),
                               request.getProperty("flavor"),
                               request.getProperty("clusterType"),
                               request.getProperty("clusterId"));
    }

    private static BindingMatch<?> getBindingMatch(HttpRequest request) {
        return URI_PATTERNS.stream()
                .map(pattern -> {
                    UriPattern.Match match = pattern.match(request.getUri());
                    if (match == null) return null;
                    return new BindingMatch<>(match, new Object(), pattern);
                })
                .filter(Objects::nonNull)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Illegal url for config request: " + request.getUri()));
    }

    private static boolean isRestartRequest(HttpRequest request) {
        return getBindingMatch(request).groupCount() == 7 &&
               request.getUri().getPath().endsWith("/restart");
    }

    private static boolean isReindexRequest(HttpRequest request) {
        return getBindingMatch(request).groupCount() == 7 &&
               request.getUri().getPath().endsWith("/reindex");
    }

    private static boolean isReindexingRequest(HttpRequest request) {
        return getBindingMatch(request).groupCount() == 7 &&
               request.getUri().getPath().endsWith("/reindexing");
    }

    private static boolean isIsSuspendedRequest(HttpRequest request) {
        return getBindingMatch(request).groupCount() == 7 &&
               request.getUri().getPath().endsWith("/suspended");
    }

    private static boolean isProtonMetricsRequest(HttpRequest request) {
        return getBindingMatch(request).groupCount() == 8 &&
                request.getUri().getPath().endsWith("/metrics/proton");
    }

    private static boolean isDeploymentMetricsRequest(HttpRequest request) {
        return getBindingMatch(request).groupCount() == 8 &&
                request.getUri().getPath().endsWith("/metrics/deployment");
    }

    private static boolean isLogRequest(HttpRequest request) {
        return getBindingMatch(request).groupCount() == 7 &&
                request.getUri().getPath().endsWith("/logs");
    }

    private static boolean isServiceConvergeListRequest(HttpRequest request) {
        return getBindingMatch(request).groupCount() == 7 &&
                request.getUri().getPath().endsWith("/serviceconverge");
    }

    private static boolean isServiceConvergeRequest(HttpRequest request) {
        return getBindingMatch(request).groupCount() == 8 &&
                request.getUri().getPath().contains("/serviceconverge/");
    }

    private static boolean isClusterControllerStatusRequest(HttpRequest request) {
        return getBindingMatch(request).groupCount() == 9 &&
                request.getUri().getPath().contains("/clustercontroller/");
    }

    private static boolean isContentRequest(HttpRequest request) {
        return getBindingMatch(request).groupCount() > 7 &&
                request.getUri().getPath().contains("/content/");
    }

    private static boolean isFiledistributionStatusRequest(HttpRequest request) {
        return getBindingMatch(request).groupCount() == 7 &&
                request.getUri().getPath().contains("/filedistributionstatus");
    }

    private static boolean isTesterRequest(HttpRequest request) {
        return getBindingMatch(request).groupCount() == 8 &&
               request.getUri().getPath().contains("/tester");
    }

    private static boolean isTesterStartTestsRequest(HttpRequest request) {
        return getBindingMatch(request).groupCount() == 9 &&
               request.getUri().getPath().contains("/tester/run/");
    }

    private static boolean isQuotaUsageRequest(HttpRequest request) {
        return getBindingMatch(request).groupCount() == 7 &&
                request.getUri().getPath().endsWith("/quota");
    }

    private static String getHostNameFromRequest(HttpRequest req) {
        BindingMatch<?> bm = getBindingMatch(req);
        return bm.group(7);
    }

    private static String getTesterCommandFromRequest(HttpRequest req) {
        BindingMatch<?> bm = getBindingMatch(req);
        return bm.group(7);
    }

    private static String getSuiteFromRequest(HttpRequest req) {
        BindingMatch<?> bm = getBindingMatch(req);
        return bm.group(8);
    }

    private static String getPathSuffix(HttpRequest req) {
        BindingMatch<?> bm = getBindingMatch(req);
        return bm.group(8);
    }

    private static ApplicationId getApplicationIdFromRequest(HttpRequest req) {
        // Two bindings for this: with full app id or only application name
        BindingMatch<?> bm = getBindingMatch(req);
        if (bm.groupCount() > 4) return createFromRequestFullAppId(bm);
        return createFromRequestSimpleAppId(bm);
    }

    // The URL pattern with only tenant and application given
    private static ApplicationId createFromRequestSimpleAppId(BindingMatch<?> bm) {
        TenantName tenant = TenantName.from(bm.group(2));
        ApplicationName application = ApplicationName.from(bm.group(3));
        return new ApplicationId.Builder().tenant(tenant).applicationName(application).build();
    }

    // The URL pattern with full app id given
    private static ApplicationId createFromRequestFullAppId(BindingMatch<?> bm) {
        String tenant = bm.group(2);
        String application = bm.group(3);
        String instance = bm.group(6);
        return new ApplicationId.Builder()
            .tenant(tenant)
            .applicationName(application).instanceName(instance)
            .build();
    }

    private static Optional<Version> getVespaVersionFromRequest(HttpRequest request) {
        String vespaVersion = request.getProperty("vespaVersion");
        return (vespaVersion == null || vespaVersion.isEmpty())
                ? Optional.empty()
                : Optional.of(Version.fromString(vespaVersion));
    }

    private static class DeleteApplicationResponse extends JSONResponse {
        DeleteApplicationResponse(int status, ApplicationId applicationId) {
            super(status);
            object.setString("message", "Application '" + applicationId + "' deleted");
        }
    }

    private static class GetApplicationResponse extends JSONResponse {
        GetApplicationResponse(int status, long generation, List<Version> modelVersions, Optional<String> applicationPackageReference) {
            super(status);
            object.setLong("generation", generation);
            object.setString("applicationPackageFileReference", applicationPackageReference.orElse(""));
            Cursor modelVersionArray = object.setArray("modelVersions");
            modelVersions.forEach(version -> modelVersionArray.addString(version.toFullString()));
        }
    }

    private static class ApplicationSuspendedResponse extends JSONResponse {
        ApplicationSuspendedResponse(boolean suspended) {
            super(Response.Status.OK);
            object.setBool("suspended", suspended);
        }
    }

    private static class QuotaUsageResponse extends JSONResponse {
        QuotaUsageResponse(double usageRate) {
            super(Response.Status.OK);
            object.setDouble("rate", usageRate);
        }
    }

    private static class ReindexResponse extends JSONResponse {
        ReindexResponse(ApplicationReindexing reindexing) {
            super(Response.Status.OK);
                object.setBool("enabled", reindexing.enabled());
                setStatus(object.setObject("status"), reindexing.common());

                Cursor clustersObject = object.setObject("clusters");
                reindexing.clusters().entrySet().stream().sorted(comparingByKey())
                          .forEach(cluster -> {
                              Cursor clusterObject = clustersObject.setObject(cluster.getKey());
                              setStatus(clusterObject.setObject("status"), cluster.getValue().common());

                              Cursor pendingObject = clusterObject.setObject("pending");
                              cluster.getValue().pending().entrySet().stream().sorted(comparingByKey())
                                     .forEach(pending -> pendingObject.setLong(pending.getKey(), pending.getValue()));

                              Cursor readyObject = clusterObject.setObject("ready");
                              cluster.getValue().ready().entrySet().stream().sorted(comparingByKey())
                                     .forEach(ready -> setStatus(readyObject.setObject(ready.getKey()), ready.getValue()));
                          });
        }

        private static void setStatus(Cursor object, ApplicationReindexing.Status status) {
            object.setLong("readyMillis", status.ready().toEpochMilli());
        }
    }

}
