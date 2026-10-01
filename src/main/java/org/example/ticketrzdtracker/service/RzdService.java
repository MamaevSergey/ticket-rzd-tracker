package org.example.ticketrzdtracker.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.ticketrzdtracker.model.TicketResult;
import org.example.ticketrzdtracker.model.dto.TrainOption;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

@Slf4j
@Service
public class RzdService {

    @Value("${rzd.api.main-page-url}")
    private String mainPageUrl;

    @Value("${rzd.api.train-pricing-url}")
    private String trainPricingUrl;

    @Value("${rzd.api.car-pricing-url}")
    private String carPricingUrl;

    @Value("${rzd.http.read-timeout-seconds:30}")
    private int readTimeoutSeconds;

    @Value("${rzd.http.max-retries:3}")
    private int maxRetries;

    @Value("${rzd.http.backoff-base-ms:2000}")
    private long backoffBaseMs;

    private static final List<String> USER_AGENTS = List.of(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
    );

    private final ObjectMapper mapper;
    private final HttpClient client;
    private String sessionCookies;

    public RzdService(
            @Value("${rzd.proxy.enabled:false}") boolean proxyEnabled,
            @Value("${rzd.proxy.host:}") String proxyHost,
            @Value("${rzd.proxy.port:0}") int proxyPort,
            @Value("${rzd.proxy.type:HTTP}") String proxyType
    ) {
        this.mapper = new ObjectMapper();

        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .version(HttpClient.Version.HTTP_2)
                .followRedirects(HttpClient.Redirect.NORMAL);

        setupSslBypass(builder);

        if (proxyEnabled && proxyHost != null && !proxyHost.isBlank() && proxyPort > 0) {
            log.info("Routing traffic through {} proxy {}:{}", proxyType, proxyHost, proxyPort);
            builder.proxy(ProxySelector.of(new InetSocketAddress(proxyHost, proxyPort)));
        }

        this.client = builder.build();
    }

    public List<TrainOption> getAvailableTrains(String originCode, String destCode, String dateStr) {
        ensureSession();
        String dateIso = convertDate(dateStr);
        if (dateIso == null) {
            return Collections.emptyList();
        }

        Map<String, String> params = new LinkedHashMap<>();

        params.put("service_provider", "B2B_RZD");
        params.put("getByLocalTime", "true");
        params.put("carGrouping", "DontGroup");
        params.put("origin", originCode);
        params.put("destination", destCode);
        params.put("departureDate", dateIso);
        params.put("carIssuingType", "Passenger");
        params.put("getTrainsFromSchedule", "true");

        String queryString = params.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(trainPricingUrl + "?" + queryString))
                .GET()
                .timeout(Duration.ofSeconds(readTimeoutSeconds))
                .header("User-Agent", getRandomUserAgent())
                .header("Cookie", sessionCookies != null ? sessionCookies : "")
                .header("x-client-id", "22900")
                .build();

        HttpResponse<String> response = executeWithBackoff(request);
        if (response == null || response.statusCode() != 200) {
            return Collections.emptyList();
        }

        return parseTrainOptions(response.body());
    }

    public List<TicketResult> checkPlatsLowerTickets(String originCode, String destCode, String dateStr, String trainNumberFilter) {
        List<TrainOption> trains = getAvailableTrains(originCode, destCode, dateStr);
        if (trains.isEmpty()) {
            return Collections.emptyList();
        }

        List<TicketResult> seats = new ArrayList<>();
        for (TrainOption train : trains) {
            if (trainNumberFilter != null && !trainNumberFilter.equalsIgnoreCase("ANY")
                    && !train.getTrainNumber().contains(trainNumberFilter)) {
                continue;
            }

            seats.addAll(fetchSeatsForTrain(originCode, destCode, train));
        }
        return seats;
    }

    private List<TicketResult> fetchSeatsForTrain(String originCode, String destCode, TrainOption train) {
        ensureSession();
        List<TicketResult> results = new ArrayList<>();

        String jsonBody = String.format("{"
                        + "\"OriginCode\": \"%s\","
                        + "\"DestinationCode\": \"%s\","
                        + "\"Provider\": \"%s\","
                        + "\"CarIssuingType\": \"Passenger\","
                        + "\"DepartureDate\": \"%s\","
                        + "\"HasPlacesForLargeFamily\": false,"
                        + "\"SpecialPlacesDemand\": \"StandardPlacesAndForDisabledPersons\","
                        + "\"TrainNumber\": \"%s\""
                        + "}",
                originCode,
                destCode,
                train.getProvider(),
                train.getDepartureDateTime(),
                train.getTrainNumber()
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(carPricingUrl))
                .timeout(Duration.ofSeconds(readTimeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .header("Content-Type", "application/json")
                .header("User-Agent", getRandomUserAgent())
                .header("Referer", "https://ticket.rzd.ru/")
                .header("Origin", "https://ticket.rzd.ru")
                .header("x-client-id", "22900")
                .header("Cookie", sessionCookies != null ? sessionCookies : "")
                .build();

        HttpResponse<String> response = executeWithBackoff(request);
        if (response == null || response.statusCode() != 200) {
            return results;
        }

        try {
            JsonNode cars = mapper.readTree(response.body()).path("Cars");
            for (JsonNode car : cars) {
                String carType = car.path("CarType").asText();
                String carTypeName = car.path("CarTypeName").asText();
                boolean isPlats = "ReservedSeat".equalsIgnoreCase(carType) || carTypeName.contains("ПЛАЦ");
                if (!isPlats) {
                    continue;
                }

                String placeType = car.path("CarPlaceType").asText();
                String placeCode = car.path("CarPlaceCode").asText();
                String placeNameRu = car.path("CarPlaceNameRu").asText();

                boolean isLower = "Lower".equalsIgnoreCase(placeType)
                        || "SideLower".equalsIgnoreCase(placeType)
                        || "Н".equalsIgnoreCase(placeCode)
                        || "У".equalsIgnoreCase(placeCode)
                        || placeNameRu.equalsIgnoreCase("Нижнее")
                        || placeNameRu.equalsIgnoreCase("Боковое нижнее");

                if (!isLower) {
                    continue;
                }

                String carNum = car.path("CarNumber").asText();
                String freePlaces = car.path("FreePlaces").asText("");
                double price = car.path("MinPrice").asDouble(0.0);

                if (!freePlaces.isBlank() && !freePlaces.equalsIgnoreCase("нет")) {
                    for (String seat : freePlaces.split(",")) {
                        try {
                            int seatNum = Integer.parseInt(seat.replaceAll("\\D", ""));
                            results.add(new TicketResult(carNum, seatNum, price, placeNameRu));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to parse seats for train {}: {}", train.getTrainNumber(), e.getMessage());
        }

        return results;
    }

    private synchronized void ensureSession() {
        if (sessionCookies != null) {
            return;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(mainPageUrl))
                    .GET()
                    .timeout(Duration.ofSeconds(readTimeoutSeconds))
                    .header("User-Agent", getRandomUserAgent())
                    .build();

            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            StringBuilder cookieBuilder = new StringBuilder();
            response.headers().allValues("Set-Cookie").forEach(c -> cookieBuilder.append(c.split(";")[0]).append("; "));
            cookieBuilder.append("acceptCookies=1; LANG_SITE=ru; x-client-id=22900;");
            this.sessionCookies = cookieBuilder.toString();
        } catch (Exception e) {
            log.error("Failed to initialize session cookies: {}", e.getMessage());
        }
    }

    private HttpResponse<String> executeWithBackoff(HttpRequest request) {
        int attempt = 0;
        long backoff = backoffBaseMs;

        while (attempt < maxRetries) {
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();

                if (status == 429 || status == 503) {
                    attempt++;
                    long jitter = ThreadLocalRandom.current().nextLong(200, 800);
                    Thread.sleep(backoff + jitter);
                    backoff *= 2;
                    continue;
                }

                if ((status == 401 || status == 403) && attempt == 0) {
                    this.sessionCookies = null;
                    ensureSession();
                    attempt++;
                    continue;
                }

                return response;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } catch (Exception e) {
                attempt++;
                if (attempt >= maxRetries) {
                    return null;
                }
                try {
                    Thread.sleep(backoff);
                    backoff *= 2;
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        return null;
    }

    private List<TrainOption> parseTrainOptions(String jsonBody) {
        List<TrainOption> trainOptions = new ArrayList<>();
        try {
            JsonNode trains = mapper.readTree(jsonBody).path("Trains");
            for (JsonNode train : trains) {
                String trainNum = train.path("DisplayTrainNumber").asText();
                String depIso = train.path("LocalDepartureDateTime").asText(train.path("DepartureDateTime").asText());
                String arrIso = train.path("LocalArrivalDateTime").asText(train.path("ArrivalDateTime").asText());
                String exactDepartureDate = train.path("DepartureDateTime").asText();
                String provider = train.has("Provider") ? train.path("Provider").asText() : "P1";

                String depTime = depIso.length() >= 16 ? depIso.substring(11, 16) : depIso;
                String arrTime = arrIso.length() >= 16 ? arrIso.substring(11, 16) : arrIso;

                trainOptions.add(new TrainOption(trainNum, depTime, arrTime, provider, exactDepartureDate));
            }
        } catch (Exception e) {
            log.error("Failed to parse train schedule response: {}", e.getMessage());
        }
        return trainOptions;
    }

    private void setupSslBypass(HttpClient.Builder builder) {
        try {
            TrustManager[] trustAllCerts = new TrustManager[]{
                    new X509TrustManager() {
                        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                        public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                        public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                    }
            };
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, new SecureRandom());
            builder.sslContext(sslContext);
        } catch (Exception e) {
            log.error("SSL configuration failed: {}", e.getMessage());
        }
    }

    private String getRandomUserAgent() {
        return USER_AGENTS.get(ThreadLocalRandom.current().nextInt(USER_AGENTS.size()));
    }

    private String convertDate(String dateStr) {
        try {
            LocalDate date = LocalDate.parse(dateStr, DateTimeFormatter.ofPattern("dd.MM.yyyy"));
            return date.atStartOfDay().toString();
        } catch (Exception e) {
            log.error("Invalid date format: {}", dateStr);
            return null;
        }
    }
}