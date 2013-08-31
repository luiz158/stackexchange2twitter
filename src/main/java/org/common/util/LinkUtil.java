package org.common.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Lists;

public final class LinkUtil {
    private static final Logger logger = LoggerFactory.getLogger(LinkUtil.class);

    public final static List<String> seDomains = Lists.newArrayList("http://stackoverflow.com/", "http://askubuntu.com/", "http://superuser.com/");

    final static List<String> bannedDomains = Lists.newArrayList(// @formatter:off
        "http://wp-plugin-archive.de",
        "http://www.blogging-inside.de", 
        "http://www.perun.net", 
        "http://www.heyyou-app.com", 
        "http://www.freelancer.com", "https://www.freelancer.com", 
        "http://www.almasryalyoum.com/", // non English
        "https://www.facebook.com" // verified - no false positive after about 2 weeks - accepting
    );// @formatter:on

    final static List<String> bannedDomainsMaybe = Lists.newArrayList(// @formatter:off
         // 
    );// @formatter:on
    final static List<String> bannedDomainsByRegexMaybe = Lists.newArrayList(// @formatter:off
        "http(s)?://(www.)?.*\\.de(\\z|/.*)"
    );// @formatter:on

    private LinkUtil() {
        throw new AssertionError();
    }

    // API

    /**
     * - local <br/>
     * - note: simplistic implementation to be improved when needed
     */
    public static boolean belongsToBannedDomain(final String urlString) {
        for (final String bannedDomain : bannedDomains) {
            if (urlString.startsWith(bannedDomain)) {
                return true;
            }
        }
        for (final String bannedDomainMaybe : bannedDomainsMaybe) {
            if (urlString.startsWith(bannedDomainMaybe)) {
                // still error - reasons to move down: +1,
                logger.error("(temp-error) For url: {} banned domain: {}", urlString, bannedDomainMaybe);
                return true;
            }
        }
        for (final String bannedDomainByRegexMaybe : bannedDomainsByRegexMaybe) {
            if (urlString.matches(bannedDomainByRegexMaybe)) {
                // still error - reasons to move down: +1,
                logger.error("(temp-error) For url: {} banned domain by regex: {}", urlString, bannedDomainByRegexMaybe);
                return true;
            }
        }

        return false;
    }

    /**
     * - <b>local</b> <br/>
     * - note: will NOT return null
     */
    public static List<String> extractUrls(final String input) {
        final List<String> result = new ArrayList<String>();

        final Pattern pattern = Pattern.compile("\\b(((ht|f)tp(s?)\\:\\/\\/|~\\/|\\/)|www.)" + "(\\w+:\\w+@)?(([-\\w]+\\.)+(com|org|net|gov" + "|mil|biz|info|mobi|name|aero|jobs|museum" + "|travel|[a-z]{2}))(:[\\d]{1,5})?"
                + "(((\\/([-\\w~!$+|.,=]|%[a-f\\d]{2})+)+|\\/)+|\\?|#)?" + "((\\?([-\\w~!$+|.,*:]|%[a-f\\d{2}])+=?" + "([-\\w~!$+|.,*:=]|%[a-f\\d]{2})*)" + "(&(?:[-\\w~!$+|.,*:]|%[a-f\\d{2}])+=?" + "([-\\w~!$+|.,*:=]|%[a-f\\d]{2})*)*)*"
                + "(#([-\\w~!$+|.,*:=]|%[a-f\\d]{2})*)?\\b");

        final Matcher matcher = pattern.matcher(input);
        while (matcher.find()) {
            result.add(matcher.group());
        }

        return result;
    }

}
