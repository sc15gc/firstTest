package diss.api;

import com.google.common.collect.Lists;
import diss.parser.AccessTokenParser;
import diss.parser.RepoNameParser;
import diss.parser.UserParser;
import diss.utils.JSONUtils;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Properties;
import java.util.StringTokenizer;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class GitHubService {

    private final String GITHUB_BASE = getGitHubBase();

    private static final Logger logger = LoggerFactory.getLogger(GitHubService.class);

    private AccessTokenParser accessTokenParser;
    private RepoNameParser repoNameParser;
    private UserParser userParser;

    @Autowired
    public GitHubService(AccessTokenParser accessTokenParser, RepoNameParser repoNameParser, UserParser userParser) {
        this.accessTokenParser = accessTokenParser;
        this.repoNameParser = repoNameParser;
        this.userParser = userParser;
    }


    @Async
    public CompletableFuture<JSONObject> retrieveRepoJsonAsync(String repoURI, String accessToken) throws IOException {
        logger.info(String.format("Retrieving generic repo info of %s", repoURI));
        String user = userParser.parse(repoURI);
        String repo = repoNameParser.parseRepoNameFromUri(repoURI);
        String response = executeGet(GITHUB_BASE + "/repos/" + user + '/' + repo + "?access_token=" + accessToken);
        logger.info(String.format("Finished retrieving generic repo info of %s", repoURI));
        return CompletableFuture.completedFuture(new JSONObject(response));
    }

    @Async
    public CompletableFuture<JSONObject> retrieveContentsOfOrganisationJsonAsync(String repoURI, String accessToken) throws IOException {
        JSONObject repos = new JSONObject();
        logger.info(String.format("Retrieving generic organisation info of %s", repoURI));
        String user = userParser.parse(repoURI);
        String response = executeGet(GITHUB_BASE + "/orgs/" + user + "/repos?access_token=" + accessToken);
        logger.info(String.format("Finished retrieving generic organisation info of %s", repoURI));
        repos.put("repositories", new JSONArray(response));
        return CompletableFuture.completedFuture(repos);
    }

    @Async
    public CompletableFuture<JSONObject> retrievePageOfCommitsAsync(String repoURI, int page, String accessToken, int amountPerPage) throws IOException {
        String user = userParser.parse(repoURI);
        String repo = repoNameParser.parseRepoNameFromUri(repoURI);
        String response = executeGet(GITHUB_BASE + "/repos/" + user + '/' + repo + "/commits?page=" + page + "&per_page=" + amountPerPage + "&access_token=" + accessToken);
        //TODO: is this bad practice? operatiing on a response object which may not have been passed back yet?
        JSONObject commits = new JSONObject();
        commits.put("commits", new JSONArray(response));
        return CompletableFuture.completedFuture(commits);
    }

    @Async
    public CompletableFuture<JSONObject> retrieveInitialPageOfCommitAsync(String repoURI, String accessToken) throws IOException {
        logger.info(String.format("Retrieving initial page of commit from %s", repoURI));
        String user = userParser.parse(repoURI);
        String repo = repoNameParser.parseRepoNameFromUri(repoURI);
        HttpClient httpClient = HttpClients.createDefault();

        HttpGet httpGet = new HttpGet(GITHUB_BASE + "/repos/" + user + '/' + repo + "/commits?page=1" + "&access_token=" + accessToken);
        HttpResponse response = httpClient.execute(httpGet);
        Header[] linkHeader = response.getHeaders("Link");
        String link;
        if (linkHeader.length == 0) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            response.getEntity().writeTo(out);
            JSONArray commitArray = new JSONArray(out.toString());
            JSONObject commits = new JSONObject();
            commits.put("commits", commitArray);

            return CompletableFuture.completedFuture(commits);
        } else {
            String headerValue = (response.getHeaders("Link")[0].getElements()[1].toString());
            link = extractLinkToLastPage(headerValue);
        }
        JSONArray commitArray = new JSONArray(executeGet(link));
        JSONObject commits = new JSONObject();
        commits.put("commits", commitArray);
        logger.info(String.format("Finished retrieving initial page of commit from %s", repoURI));
        return CompletableFuture.completedFuture(commits);
    }

    @Async
    public CompletableFuture<JSONObject> retrieveSpecificCommitAsync(String repoURI, String sha, String accessToken) throws IOException {
        logger.info(String.format("retrieving commit %s from repoURI %s", sha, repoURI));
        String user = userParser.parse(repoURI);
        String repo = repoNameParser.parseRepoNameFromUri(repoURI);

        String response = executeGet(GITHUB_BASE + "/repos/" + user + '/' + repo + "/commits/" + sha + "?access_token=" + accessToken);
        return CompletableFuture.completedFuture(new JSONObject(response));

    }

    @Async
    public CompletableFuture<JSONObject> retrieveContentsOfRepoAsync(String repoURI, String accessToken) throws IOException {
        logger.info(String.format("Retrieving contents of repo %s", repoURI));
        String user = userParser.parse(repoURI);
        String repo = repoNameParser.parseRepoNameFromUri(repoURI);
        String response = executeGet(GITHUB_BASE + "/repos/" + user + '/' + repo + "/contents?access_token=" + accessToken);
        JSONObject contents = new JSONObject();
        contents.put("contents", new JSONArray(response));
        logger.info(String.format("Finished retrieving contents of repo %s", repoURI));

        return CompletableFuture.completedFuture(contents);
    }

    @Async
    public CompletableFuture<String> retrieveFromUrl(String url) throws IOException {
        logger.info(String.format("Retrieving contents from url: %s", url));
        return CompletableFuture.completedFuture(executeGet(url));
    }


    @Async
    public CompletableFuture<String> convertToMarkdown(String text, String accessToken) throws IOException {
        JSONObject postData = new JSONObject();
        postData.put("text", text);
        postData.put("mode", "gfm");

        HttpClient httpClient = HttpClients.createDefault();
        HttpPost httpPost = new HttpPost(getGitHubBase() + "/markdown?access_token=" + accessToken);
        StringEntity params = new StringEntity(postData.toString());
        httpPost.addHeader("content-type", "application/x-www-form-urlencoded");
        httpPost.setEntity(params);

        HttpResponse response = httpClient.execute(httpPost);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        response.getEntity().writeTo(out);
        String responseString = out.toString();

        return CompletableFuture.completedFuture(responseString);
    }

    static String getGitHubBase() {
        try {
            Properties apiProperties = new Properties();
            apiProperties.load(new FileInputStream("./src/main/resources/properties/api.properties"));
            return (apiProperties.getProperty("github.api.url"));
        } catch (Exception e) {
            return "cannot find github api url";
        }
    }

    public JSONObject retrievePageOfContributors(String repoURI, int page, String accessToken) throws IOException {
        //TODO: remove if never used (also convert to async)

        String user = userParser.parse(repoURI);
        String repo = repoNameParser.parseRepoNameFromUri(repoURI);
        String response = executeGet(GITHUB_BASE + "/repos/" + user + '/' + repo + "/contributors?page=" + page +
                "&per_page=100" + "&access_token=" + accessToken);
        JSONArray contributors = new JSONArray(response);
        JSONObject contributorsJsonReturn = new JSONObject();
        contributorsJsonReturn.put("contributors", contributors);
        return contributorsJsonReturn;
    }

    public JSONObject retrieveAllContributorsJsons(String repoURI, String accessToken) throws IOException {
        HttpClient httpClient = HttpClients.createDefault();
        String user = userParser.parse(repoURI);
        String repo = repoNameParser.parseRepoNameFromUri(repoURI);

        HttpGet httpGet = new HttpGet(GITHUB_BASE + "/repos/" + user + '/' + repo +
                "/contributors?per_page=100&anon=1" + "&access_token=" + accessToken);
        HttpResponse response = httpClient.execute(httpGet);
        Header[] linkHeader = response.getHeaders("Link");
        int maxPages;
        if (linkHeader.length == 0) {
            maxPages = 1;
        } else {
            String headerValue = (response.getHeaders("Link")[0].getElements()[1].getValue());
            maxPages = getPageNumberFromValue(headerValue);
        }

        List<JSONArray> pages = retrievePagesUntilMax(user, repo, maxPages, accessToken);
        JSONArray allContributors = JSONUtils.stitchArrays(pages);

        JSONObject returnJson = new JSONObject();
        returnJson.put("contributors", allContributors);

        return returnJson;
    }

    public JSONObject compareTwoCommits(String repoURI, String accessToken, String firstSha, String secondSha) throws IOException {
        String user = userParser.parse(repoURI);
        String repo = repoNameParser.parseRepoNameFromUri(repoURI);
        return compareTwoCommits(user, repo, accessToken, firstSha, secondSha);
    }

    public JSONObject compareTwoCommits(String user, String repo, String accessToken, String firstSha, String secondSha) throws IOException {

        String response = executeGet(GITHUB_BASE + "/repos/" + user + '/' + repo + "/compare/" + firstSha + "..."
                + secondSha + "?access_token=" + accessToken);

        return new JSONObject(response);
    }

    public String retrieveAccessToken(String code, String clientId, String accessToken) {
        try {
            HttpClient httpClient = HttpClients.createDefault();
            HttpPost httpPost = new HttpPost("https://github.com/login/oauth/access_token");
            httpPost.setEntity(new UrlEncodedFormEntity(Lists.newArrayList(
                    new BasicNameValuePair("client_id", clientId),
                    new BasicNameValuePair("client_secret", accessToken),
                    new BasicNameValuePair("code", code))));
            HttpResponse response = httpClient.execute(httpPost);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            response.getEntity().writeTo(out);
            return accessTokenParser.parseAccessCode(out.toString());
        } catch (IOException e) {
            return "";
        }

    }

    private String executeGet(String url) throws IOException {
        HttpClient httpClient = HttpClients.createDefault();
        HttpGet httpGet = new HttpGet(url);
        HttpResponse response = httpClient.execute(httpGet);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        response.getEntity().writeTo(out);
        return out.toString();
    }

    private String executePost(String url, List<NameValuePair> params) throws IOException {
        HttpClient httpClient = HttpClients.createDefault();
        HttpPost httpPost = new HttpPost(url);
        httpPost.setEntity(new UrlEncodedFormEntity(params, "UTF-8"));
        HttpResponse response = httpClient.execute(httpPost);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        response.getEntity().writeTo(out);
        return out.toString();
    }

    List<JSONArray> retrievePagesUntilMax(String user, String repo, int maxPage, String accessToken) throws IOException {
        List<JSONArray> pageList = Lists.newArrayList();
        for (int i = 1; i <= maxPage; i++) {
            String uri = GITHUB_BASE + "/repos/" + user + '/' + repo + "/contributors?" + "page=" + i + "&per_page=100" + "&access_token=" + accessToken;
            HttpGet httpGet = new HttpGet(uri);
            HttpClient httpClient = HttpClients.createDefault();
            HttpResponse response = httpClient.execute(httpGet);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            response.getEntity().writeTo(out);
            pageList.add(new JSONArray(out.toString()));
        }
        return pageList;
    }

    static int getPageNumberFromValue(String value) {
        Pattern p = Pattern.compile("page=[0-9]*");
        Matcher m = p.matcher(value);
        m.find();
        String page = m.group();
        return Integer.parseInt(page.replaceAll("[^0-9]", ""));
    }

    public static String extractLinkToLastPage(String XML) {
        StringTokenizer stringTokenizer = new StringTokenizer(XML, "<>");
        return stringTokenizer.nextToken();
    }

}
