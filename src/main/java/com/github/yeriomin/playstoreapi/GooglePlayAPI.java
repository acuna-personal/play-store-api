package com.github.yeriomin.playstoreapi;

import java.io.IOException;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringTokenizer;

/**
 * This class provides
 * <code>checkin, search, details, bulkDetails, browse, list and download</code>
 * capabilities. It uses <code>Apache Commons HttpClient</code> for POST and GET
 * requests.
 *
 * @author akdeniz, yeriomin
 */
public class GooglePlayAPI {

    private static final int IMAGE_TYPE_APP_SCREENSHOT = 1;
    private static final int IMAGE_TYPE_PLAY_STORE_PAGE_BACKGROUND = 2;
    private static final int IMAGE_TYPE_YOUTUBE_VIDEO_LINK = 3;
    private static final int IMAGE_TYPE_APP_ICON = 4;
    private static final int IMAGE_TYPE_CATEGORY_ICON = 5;

    private static final String SCHEME = "https://";
    private static final String HOST = "android.clients.google.com";
    private static final String CHECKIN_URL = SCHEME + HOST + "/checkin";
    private static final String URL_LOGIN = SCHEME + HOST + "/auth";
    private static final String C2DM_REGISTER_URL = SCHEME + HOST + "/c2dm/register2";
    public static final String FDFE_URL = SCHEME + HOST + "/fdfe/";
    public static final String LIST_URL = FDFE_URL + "list";
    private static final String BROWSE_URL = FDFE_URL + "browse";
    private static final String DETAILS_URL = FDFE_URL + "details";
    public static final String SEARCH_URL = FDFE_URL + "search";
    private static final String SEARCHSUGGEST_URL = FDFE_URL + "searchSuggest";
    private static final String BULKDETAILS_URL = FDFE_URL + "bulkDetails";
    private static final String PURCHASE_URL = FDFE_URL + "purchase";
    private static final String DELIVERY_URL = FDFE_URL + "delivery";
    private static final String REVIEWS_URL = FDFE_URL + "rev";
    private static final String ADD_REVIEW_URL = FDFE_URL + "addReview";
    private static final String DELETE_REVIEW_URL = FDFE_URL + "deleteReview";
    private static final String UPLOADDEVICECONFIG_URL = FDFE_URL + "uploadDeviceConfig";
    private static final String RECOMMENDATIONS_URL = FDFE_URL + "rec";
    private static final String CATEGORIES_URL = FDFE_URL + "categories";

    private static final String ACCOUNT_TYPE_HOSTED_OR_GOOGLE = "HOSTED_OR_GOOGLE";

    public enum REVIEW_SORT {
        NEWEST(0), HIGHRATING(1), HELPFUL(4);

        public int value;

        REVIEW_SORT(int value) {
            this.value = value;
        }
    }

    public enum RECOMMENDATION_TYPE {
        ALSO_VIEWED(1), ALSO_INSTALLED(2);

        public int value;

        RECOMMENDATION_TYPE(int value) {
            this.value = value;
        }
    }

    public enum SEARCH_SUGGESTION_TYPE {
        SEARCH_STRING(2), APP(3);

        public int value;

        SEARCH_SUGGESTION_TYPE(int value) {
            this.value = value;
        }
    }

    public enum SUBCATEGORY {
        TOP_FREE("apps_topselling_free"), TOP_GROSSING("apps_topgrossing"), MOVERS_SHAKERS("apps_movers_shakers");

        public String value;

        SUBCATEGORY(String value) {
            this.value = value;
        }
    }

    OkHttpClientWrapper client;
    private Locale locale;
    private DeviceInfoProvider deviceInfoProvider;

    /**
     * Auth token
     * Seems to have a very long lifetime - months
     * So, it is a good idea to save and reuse it
     */
    private String token;

    /**
     * Google Services Framework id
     * Incorrectly called Android id and Device id sometimes
     * Is generated by a checkin request in generateGsfId()
     * It is a good idea to save and reuse it
     */
    private String gsfId;

    public OkHttpClientWrapper getClient() {
        if (this.client == null) {
            this.client = new OkHttpClientWrapper();
        }
        return this.client;
    }

    public void setLocale(Locale locale) {
        this.locale = locale;
    }

    public void setDeviceInfoProvider(DeviceInfoProvider deviceInfoProvider) {
        this.deviceInfoProvider = deviceInfoProvider;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public void setGsfId(String gsfId) {
        this.gsfId = gsfId;
    }

    public String getToken() {
        return token;
    }

    public String getGsfId() {
        return gsfId;
    }

    /**
     * Performs authentication on "ac2dm" service and match up gsf id,
     * security token and email by checking them in on this server.
     * <p>
     * This function sets check-inded gsf id and that can be taken either by
     * using <code>generateToken()</code> or from returned
     * {@link AndroidCheckinResponse} instance.
     */
    public String generateGsfId(String email, String ac2dmToken) throws IOException {
        // this first checkin is for generating android-id
        AndroidCheckinRequest request = this.deviceInfoProvider.generateAndroidCheckinRequest();
        AndroidCheckinResponse checkinResponse1 = checkin(request.toByteArray());
        String securityToken = BigInteger.valueOf(checkinResponse1.getSecurityToken()).toString(16);

        // this is the second checkin to match credentials with android-id
        AndroidCheckinRequest.Builder checkInbuilder = AndroidCheckinRequest.newBuilder(request);
        String gsfId = BigInteger.valueOf(checkinResponse1.getAndroidId()).toString(16);
        AndroidCheckinRequest build = checkInbuilder
                .setId(new BigInteger(gsfId, 16).longValue())
                .setSecurityToken(new BigInteger(securityToken, 16).longValue())
                .addAccountCookie("[" + email + "]")
                .addAccountCookie(ac2dmToken)
                .build();
        checkin(build.toByteArray());

        return gsfId;
    }

    /**
     * Posts given check-in request content and returns
     * {@link AndroidCheckinResponse}.
     */
    private AndroidCheckinResponse checkin(byte[] request) throws IOException {
        Map<String, String> headers = getDefaultHeaders();
        headers.put("Content-Type", "application/x-protobuffer");
        byte[] content = getClient().post(CHECKIN_URL, request, headers);
        return AndroidCheckinResponse.parseFrom(content);
    }

    /**
     * Authenticates on server with given email and password and sets
     * authentication token. This token can be used to login instead of using
     * email and password every time.
     */
    public String generateToken(String email, String password) throws IOException {
        Map<String, String> params = getDefaultLoginParams(email, password);
        params.put("service", "androidmarket");
        params.put("app", "com.android.vending");
        byte[] responseBytes = getClient().post(URL_LOGIN, params, getDefaultHeaders());
        Map<String, String> response = parseResponse(new String(responseBytes));
        if (response.containsKey("Auth")) {
            return response.get("Auth");
        } else {
            throw new GooglePlayException("Authentication failed! (login)");
        }
    }

    /**
     * Logins AC2DM server and returns authentication string.
     *
     * client_sig is SHA1 digest of encoded certificate on
     * <i>GoogleLoginService(package name : com.google.android.gsf)</i> system APK.
     * But google doesn't seem to care of value of this parameter.
     */
    public String generateAC2DMToken(String email, String password) throws IOException {
        Map<String, String> params = getDefaultLoginParams(email, password);
        params.put("service", "ac2dm");
        params.put("add_account", "1");
        params.put("app", "com.google.android.gsf");
        byte[] responseBytes = getClient().post(URL_LOGIN, params, getDefaultHeaders());
        Map<String, String> response = parseResponse(new String(responseBytes));
        if (response.containsKey("Auth")) {
            return response.get("Auth");
        } else {
            throw new GooglePlayException("Authentication failed! (loginAC2DM)");
        }
    }

    public Map<String, String> c2dmRegister(String application, String sender, String email, String password) throws IOException {
        Map<String, String> params = new HashMap<>();
        params.put("app", application);
        params.put("sender", sender);
        params.put("device", new BigInteger(this.gsfId, 16).toString());
        Map<String, String> headers = getDefaultHeaders();
        headers.put("Authorization", "GoogleLogin auth=" + generateAC2DMToken(email, password));
        byte[] responseBytes = getClient().post(C2DM_REGISTER_URL, params, headers);
        return parseResponse(new String(responseBytes));
    }

    /**
     * A quick search which returns the most relevent app and a list of suggestions of current query continuation
     * In native Play Store this is used to fetch search suggestions as you type
     */
    public SearchSuggestResponse searchSuggest(String query) throws IOException {
        return searchSuggest(query, SEARCH_SUGGESTION_TYPE.SEARCH_STRING);
    }

    public SearchSuggestResponse searchSuggest(String query, SEARCH_SUGGESTION_TYPE type) throws IOException {
        Map<String, String> params = getDefaultGetParams();
        params.put("q", query);
        params.put("ssis", "120");
        params.put("sst", Integer.toString(type.value));

        byte[] responseBytes = getClient().get(SEARCHSUGGEST_URL, params, getDefaultHeaders());
        return ResponseWrapper.parseFrom(responseBytes).getPayload().getSearchSuggestResponse();
    }

    /**
     * Fetches detailed information about passed package name.
     * If you need to fetch information about more than one application, consider using bulkDetails.
     */
    public DetailsResponse details(String packageName) throws IOException {
        Map<String, String> params = new HashMap<>();
        params.put("doc", packageName);

        byte[] responseBytes = getClient().get(DETAILS_URL, params, getDefaultHeaders());
        ResponseWrapper w = ResponseWrapper.parseFrom(responseBytes);

        DetailsResponse detailsResponse = w.getPayload().getDetailsResponse();
        DetailsResponse.Builder detailsBuilder = DetailsResponse.newBuilder(detailsResponse);
        DocV2.Builder docV2Builder = DocV2.newBuilder(detailsResponse.getDocV2());
        for (PreFetch prefetch: w.getPreFetchList()) {
            Payload subPayload = prefetch.getResponse().getPayload();
            if (subPayload.hasListResponse()) {
                docV2Builder.addChild(subPayload.getListResponse().getDocList().get(0));
            }
            if (subPayload.hasReviewResponse()) {
                detailsBuilder.setUserReview(subPayload.getReviewResponse().getGetResponse().getReview(0));
            }
        }
        return detailsBuilder.setDocV2(docV2Builder).build();
    }

    /**
     * Fetches detailed information about each of the package names specified
     */
    public BulkDetailsResponse bulkDetails(List<String> packageNames) throws IOException {
        BulkDetailsRequest.Builder bulkDetailsRequestBuilder = BulkDetailsRequest.newBuilder();
        bulkDetailsRequestBuilder.addAllDocid(packageNames);
        byte[] request = bulkDetailsRequestBuilder.build().toByteArray();

        byte[] responseBytes = getClient().post(BULKDETAILS_URL, request, getDefaultHeaders());
        return ResponseWrapper.parseFrom(responseBytes).getPayload().getBulkDetailsResponse();
    }

    /**
     * Fetches available categories
     */
    public BrowseResponse browse() throws IOException {
        return browse(null, null);
    }

    public BrowseResponse browse(String categoryId, String subCategoryId) throws IOException {
        Map<String, String> params = getDefaultGetParams();
        if (null != categoryId && categoryId.length() > 0) {
            params.put("cat", categoryId);
        }
        if (null != subCategoryId && subCategoryId.length() > 0) {
            params.put("ctr", subCategoryId);
        }
        byte[] responseBytes = getClient().get(BROWSE_URL, params, getDefaultHeaders());
        return ResponseWrapper.parseFrom(responseBytes).getPayload().getBrowseResponse();
    }

    /**
     * This function is used for fetching download url and download cookie,
     * rather than actual purchasing.
     */
    public BuyResponse purchase(String packageName, int versionCode, int offerType) throws IOException {
        Map<String, String> params = new HashMap<>();
        params.put("ot", String.valueOf(offerType));
        params.put("doc", packageName);
        params.put("vc", String.valueOf(versionCode));
        byte[] responseBytes = getClient().post(PURCHASE_URL, params, getDefaultHeaders());
        return ResponseWrapper.parseFrom(responseBytes).getPayload().getBuyResponse();
    }

    /**
     * Gets download links for an already purchased app. Can be used instead of purchase() for paid apps.
     * There is no point in using delivery() for free apps, because you still have to purchase() them
     * and purchase() returns the download info.
     *
     * @param packageName
     * @param versionCode
     * @param offerType
     */
    public DeliveryResponse delivery(String packageName, int versionCode, int offerType) throws IOException {
        Map<String, String> params = new HashMap<>();
        params.put("ot", String.valueOf(offerType));
        params.put("doc", packageName);
        params.put("vc", String.valueOf(versionCode));
        byte[] responseBytes = getClient().get(DELIVERY_URL, params, getDefaultHeaders());
        return ResponseWrapper.parseFrom(responseBytes).getPayload().getDeliveryResponse();
    }

    /**
     * Fetches the reviews of given package name by sorting passed choice.
     * <p>
     * Default values for offset and numberOfResults are "0" and "20" respectively.
     * If you request more than 20 reviews, you might get a malformed request exception.
     *
     * Supply version code to only get reviews for that version of the app
     */
    public ReviewResponse reviews(String packageName, REVIEW_SORT sort, Integer offset, Integer numberOfResults, Integer versionCode) throws IOException {
        // If you request more than 20 reviews, don't be surprised if you get a MalformedRequest exception
        Map<String, String> params = getDefaultGetParams(offset, numberOfResults);
        if (null != versionCode) {
            params.put("vc", String.valueOf(versionCode));
        }
        // "This device only" flag
        // Doesn't work properly even in Google Play Store
        // Also not implementing this because method signature gets fat
        // params.put("dfil", "1");
        params.put("doc", packageName);
        params.put("sort", (sort == null) ? null : String.valueOf(sort.value));
        byte[] responseBytes = getClient().get(REVIEWS_URL, params, getDefaultHeaders());
        return ResponseWrapper.parseFrom(responseBytes).getPayload().getReviewResponse();
    }

    public ReviewResponse reviews(String packageName, REVIEW_SORT sort, Integer offset, Integer numberOfResults) throws IOException {
        return reviews(packageName, sort, offset, numberOfResults, null);
    }

    /**
     * Adds a review
     * Only package name and rating are mandatory
     *
     * @param packageName
     * @param comment
     * @param title
     * @param stars
     */
    public ReviewResponse addOrEditReview(String packageName, String comment, String title, int stars) throws IOException {
        Map<String, String> params = new HashMap<>();
        params.put("doc", packageName);
        params.put("title", title);
        params.put("content", comment);
        params.put("rating", String.valueOf(stars));
        // I don't know what these do, but Google Play Store sends these
        // params.put("ipr", "true");
        // params.put("itpr", "false");
        byte[] responseBytes = getClient().post(ADD_REVIEW_URL, params, null, getDefaultHeaders());
        return ResponseWrapper.parseFrom(responseBytes).getPayload().getReviewResponse();
    }

    public void deleteReview(String packageName) throws IOException {
        Map<String, String> params = new HashMap<>();
        params.put("doc", packageName);
        // Some unknown parameter Google Play Store sends
        // params.put("itpr", "false");
        getClient().post(DELETE_REVIEW_URL, params, getDefaultHeaders());
    }

    /**
     * Uploads device configuration to google server so that can be seen from
     * web as a registered device!!
     * If this is not done, some apps magically disappear from search responses
     */
    public UploadDeviceConfigResponse uploadDeviceConfig() throws IOException {
        UploadDeviceConfigRequest request = UploadDeviceConfigRequest.newBuilder()
                .setDeviceConfiguration(this.deviceInfoProvider.getDeviceConfigurationProto())
                .build();
        Map<String, String> headers = getDefaultHeaders();
        headers.put("X-DFE-Enabled-Experiments", "cl:billing.select_add_instrument_by_default");
        headers.put("X-DFE-Unsupported-Experiments", "nocache:billing.use_charging_poller,market_emails,buyer_currency,prod_baseline,checkin.set_asset_paid_app_field,shekel_test,content_ratings,buyer_currency_in_app,nocache:encrypted_apk,recent_changes");
        headers.put("X-DFE-Client-Id", "am-android-google");
        headers.put("X-DFE-SmallestScreenWidthDp", "320");
        headers.put("X-DFE-Filter-Level", "3");
        byte[] responseBytes = getClient().post(UPLOADDEVICECONFIG_URL, request.toByteArray(), headers);
        return ResponseWrapper.parseFrom(responseBytes).getPayload().getUploadDeviceConfigResponse();
    }

    /**
     * Fetches the recommendations of given package name.
     * <p>
     * Default values for offset and numberOfResult are "0" and "20"
     * respectively. These values are determined by Google Play Store.
     */
    public ListResponse recommendations(String packageName, RECOMMENDATION_TYPE type, Integer offset, Integer numberOfResults) throws IOException {
        Map<String, String> params = getDefaultGetParams(offset, numberOfResults);
        params.put("doc", packageName);
        params.put("rt", (type == null) ? null : String.valueOf(type.value));
        byte[] responseBytes = getClient().get(RECOMMENDATIONS_URL, params, getDefaultHeaders());
        return ResponseWrapper.parseFrom(responseBytes).getPayload().getListResponse();
    }

    /**
     * Fetches top level categories list
     */
    public BrowseResponse categories() throws IOException {
        return categories(null);
    }

    /**
     * Fetches sub categories of the given category
     */
    public BrowseResponse categories(String category) throws IOException {
        Map<String, String> params = getDefaultGetParams();
        if (null != category && category.length() > 0) {
            params.put("cat", category);
        }
        byte[] responseBytes = getClient().get(CATEGORIES_URL, params, getDefaultHeaders());
        return ResponseWrapper.parseFrom(responseBytes).getPayload().getBrowseResponse();
    }

    /**
     * Use this with the urls which play store returns: next page urls, suggests and so on
     *
     */
    public Payload genericGet(String url, Map<String, String> params) throws IOException {
        if (null == params) {
            params = new HashMap<>();
        }
        if (!params.containsKey("c")) {
            params.put("c", "3");
        }
        byte[] responseBytes = getClient().get(url, params, getDefaultHeaders());
        return ResponseWrapper.parseFrom(responseBytes).getPayload();
    }

    /**
     * login methods use this
     * Most likely not all of these are required, but the Market app sends them, so we will too
     *
     */
    private Map<String, String> getDefaultLoginParams(String email, String password) {
        Map<String, String> params = new HashMap<>();
        params.put("Email", email);
        params.put("Passwd", password);
        params.put("accountType", ACCOUNT_TYPE_HOSTED_OR_GOOGLE);
        params.put("has_permission", "1");
        params.put("source", "android");
        params.put("device_country", this.locale.getCountry().toLowerCase());
        params.put("lang", this.locale.getLanguage().toLowerCase());
        params.put("sdk_version", String.valueOf(this.deviceInfoProvider.getSdkVersion()));
        params.put("client_sig", "38918a453d07199354f8b19af05ec6562ced5788");
        return params;
    }

    /**
     * Using Accept-Language you can fetch localized informations such as reviews and descriptions.
     * Note that changing this value has no affect on localized application list that
     * server provides. It depends on only your IP location.
     *
     */
    private Map<String, String> getDefaultHeaders() {
        Map<String, String> headers = new HashMap<>();
        if (this.token != null && this.token.length() > 0) {
            headers.put("Authorization", "GoogleLogin auth=" + this.token);
        }
        headers.put("User-Agent", this.deviceInfoProvider.getUserAgentString());
        if (this.gsfId != null && this.gsfId.length() > 0) {
            headers.put("X-DFE-Device-Id", this.gsfId);
        }
        headers.put("Accept-Language", this.locale.toString().replace("_", "-"));
        // This is an encoded comma separated list of ints
        // Getting this list properly will be a huge task, so it is static for now
        // It probably depends both on device and account settings and is retrieved when the user logs in for the first time
        headers.put("X-DFE-Encoded-Targets", "CAEScFfqlIEG6gUYogFWrAISK1WDAg+hAZoCDgIU1gYEOIACFkLMAeQBnASLATlASUuyAyqCAjY5igOMBQzfA/IClwFbApUC4ANbtgKVAS7OAX8YswHFBhgDwAOPAmGEBt4OfKkB5weSB5AFASkiN68akgMaxAMSAQEBA9kBO7UBFE1KVwIDBGs3go6BBgEBAgMECQgJAQIEAQMEAQMBBQEBBAUEFQYCBgUEAwMBDwIBAgOrARwBEwMEAg0mrwESfTEcAQEKG4EBMxghChMBDwYGASI3hAEODEwXCVh/EREZA4sBYwEdFAgIIwkQcGQRDzQ2fTC2AjfVAQIBAYoBGRg2FhYFBwEqNzACJShzFFblAo0CFxpFNBzaAd0DHjIRI4sBJZcBPdwBCQGhAUd2A7kBLBVPngEECHl0UEUMtQETigHMAgUFCc0BBUUlTywdHDgBiAJ+vgKhAU0uAcYCAWQ/5ALUAw1UwQHUBpIBCdQDhgL4AY4CBQICjARbGFBGWzA1CAEMOQH+BRAOCAZywAIDyQZ2MgM3BxsoAgUEBwcHFia3AgcGTBwHBYwBAlcBggFxSGgIrAEEBw4QEqUCASsWadsHCgUCBQMD7QICA3tXCUw7ugJZAwGyAUwpIwM5AwkDBQMJA5sBCw8BNxBVVBwVKhebARkBAwsQEAgEAhESAgQJEBCZATMdzgEBBwG8AQQYKSMUkAEDAwY/CTs4/wEaAUt1AwEDAQUBAgIEAwYEDx1dB2wGeBFgTQ");
        return headers;
    }

    private Map<String, String> getDefaultGetParams() {
        return getDefaultGetParams(null, null);
    }

    /**
     * Most list requests (apps, categories,..) take these params
     *
     * @param offset
     * @param numberOfResults
     */
    private Map<String, String> getDefaultGetParams(Integer offset, Integer numberOfResults) {
        Map<String, String> params = new HashMap<>();
        // "c=3" is to get apps only, not books, music, or movies
        params.put("c", "3");
        if (offset != null) {
            params.put("o", String.valueOf(offset));
        }
        if (numberOfResults != null) {
            params.put("n", String.valueOf(numberOfResults));
        }
        return params;
    }

    /**
     * Some methods instead of a protobuf return key-value pairs on each string
     *
     * @param response
     */
    public static Map<String, String> parseResponse(String response) {
        Map<String, String> keyValueMap = new HashMap<>();
        StringTokenizer st = new StringTokenizer(response, "\n\r");
        while (st.hasMoreTokens()) {
            String[] keyValue = st.nextToken().split("=", 2);
            if (keyValue.length >= 2) {
                keyValueMap.put(keyValue[0], keyValue[1]);
            }
        }
        return keyValueMap;
    }
}
