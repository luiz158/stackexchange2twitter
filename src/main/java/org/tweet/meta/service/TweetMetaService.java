package org.tweet.meta.service;

import java.io.IOException;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.common.service.HttpService;
import org.common.service.classification.ClassificationService;
import org.common.text.TextUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.social.twitter.api.Tweet;
import org.springframework.stereotype.Service;
import org.tweet.meta.persistence.dao.IRetweetJpaDAO;
import org.tweet.meta.persistence.model.Retweet;
import org.tweet.twitter.component.MaxRtRetriever;
import org.tweet.twitter.component.TwitterHashtagsRetriever;
import org.tweet.twitter.service.TagRetrieverService;
import org.tweet.twitter.service.TwitterService;
import org.tweet.twitter.util.TwitterUtil;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.base.Splitter;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;

@Service
public class TweetMetaService {
    final Logger logger = LoggerFactory.getLogger(getClass());

    @Autowired
    private TwitterService twitterService;

    @Autowired
    private TagRetrieverService tagService;

    @Autowired
    private ClassificationService classificationService;

    @Autowired
    private HttpService httpService;

    @Autowired
    private IRetweetJpaDAO retweetApi;

    @Autowired
    private TwitterHashtagsRetriever twitterHashtagsRetriever;
    @Autowired
    private MaxRtRetriever maxRtRetriever;

    public TweetMetaService() {
        super();
    }

    // API

    // write
    public boolean retweetByHashtag(final String twitterAccount) throws JsonProcessingException, IOException {
        final String twitterTag = tagService.pickTwitterTag(twitterAccount);
        return retweetByHashtag(twitterAccount, twitterTag);
    }

    boolean retweetByHashtag(final String twitterAccount, final String twitterTag) throws JsonProcessingException, IOException {
        try {
            final boolean success = retweetByHashtagInternal(twitterAccount, twitterTag);
            if (!success) {
                logger.warn("Unable to retweet any tweet on twitterAccount = {}, by twitterTag = {}", twitterAccount, twitterTag);
            }
            return success;
        } catch (final RuntimeException runtimeEx) {
            logger.error("Unexpected exception when trying to retweet", runtimeEx);
        }

        return false;
    }

    // util

    final boolean retweetByHashtagInternal(final String twitterAccount, final String hashtag) throws JsonProcessingException, IOException {
        logger.debug("Begin trying to retweet on twitterAccount = {}", twitterAccount);

        logger.trace("Trying to retweet on twitterAccount = {}", twitterAccount);
        final List<Tweet> tweetsOfHashtag = twitterService.listTweetsOfHashtag(twitterAccount, hashtag);
        Collections.sort(tweetsOfHashtag, Ordering.from(new Comparator<Tweet>() {
            @Override
            public final int compare(final Tweet t1, final Tweet t2) {
                return t2.getRetweetCount().compareTo(t1.getRetweetCount());
            }
        }));

        return tryRetweetByHashtagInternal(twitterAccount, tweetsOfHashtag, hashtag);
    }

    private final boolean tryRetweetByHashtagInternal(final String twitterAccountName, final List<Tweet> potentialTweets, final String hashtag) throws IOException, JsonProcessingException {
        for (final Tweet potentialTweet : potentialTweets) {
            final long tweetId = potentialTweet.getId();
            logger.trace("If not already retweeted, considering to retweet on account= {}, tweetId= {}", twitterAccountName, tweetId);

            if (!hasThisAlreadyBeenTweeted(tweetId)) {
                final boolean success = tryRetweetOne(potentialTweet, twitterAccountName, hashtag);
                if (!success) {
                    logger.trace("Didn't retweet on account= {}, tweet text= {}", twitterAccountName, potentialTweet.getText());
                    continue;
                } else {
                    logger.info("Successfully retweeted on account= {}, tweet text= {}", twitterAccountName, potentialTweet.getText());
                    break;
                }
            }
        }

        return false;
    }

    private final boolean tryRetweetOne(final Tweet potentialTweet, final String twitterAccountName, final String hashtag) {
        final long tweetId = potentialTweet.getId();
        logger.trace("Considering to retweet on account= {}, tweetId= {}", twitterAccountName, tweetId);

        // is it worth it by itself?
        if (!isTweetWorthRetweetingByItself(potentialTweet, hashtag)) {
            return false;
        }

        // is it worth it in the context of all the current list of tweets?
        if (!isTweetWorthRetweetingInContext(potentialTweet, hashtag)) {
            return false;
        }

        final String text = potentialTweet.getText();
        final String tweetText = preValidityProcess(text);

        // is it valid?
        if (!TwitterUtil.isTweetTextValid(tweetText)) {
            logger.debug("Tweet invalid (size, link count) on account= {}, tweet text= {}", twitterAccountName, tweetText);
            return false;
        }

        // is this tweet pointing to something good?
        if (!isTweetPointingToSomethingGood(text)) {
            logger.debug("Tweet not pointing to something good on account= {}, tweet text= {}", twitterAccountName, tweetText);
            return false;
        }

        // is the tweet rejected by some classifier?
        if (isTweetRejectedByClassifier(text)) {
            logger.debug("Tweet rejected by a classifier on account= {}, tweet text= {}", twitterAccountName, tweetText);
            return false;
        }

        logger.info("Retweeting: text= {}; \n --- Additional meta info: id= {}, rt= {}", tweetText, tweetId, potentialTweet.getRetweetCount());

        tweet(tweetText, twitterAccountName);
        markTweetRetweeted(tweetId, twitterAccountName);
        return true;
    }

    private boolean isTweetRejectedByClassifier(final String text) {
        if (classificationService.isCommercial(text)) {
            // return true;
            return false; // temporarily, until there is more classification training data for commercial-noncommercial
        }
        return false;
    }

    private boolean isTweetPointingToSomethingGood(final String potentialTweet) {
        String singleMainUrl = TextUtils.extractUrls(potentialTweet).get(0);
        try {
            singleMainUrl = httpService.expand(singleMainUrl);
        } catch (final RuntimeException ex) {
            logger.error("Unable to expand URL: " + singleMainUrl, ex); // may become warn
            return false;
        } catch (final IOException ioEx) {
            logger.error("Unable to expand URL: " + singleMainUrl, ioEx);
            return false;
        }

        if (httpService.isHomepageUrl(singleMainUrl)) {
            return false;
        }

        return true;
    }

    /**
     * Determines if a tweet is worth retweeting based on the following criteria: 
     * - has link
     * - contains any banned keywords
     */
    private boolean isTweetWorthRetweetingByItself(final Tweet potentialTweet, final String hashtag) {
        if (!containsLink(potentialTweet.getText())) {
            return false;
        }
        if (TwitterUtil.tweetContainsBannedKeywords(potentialTweet.getText())) {
            return false;
        }
        if (isRetweet(potentialTweet)) {
            return false;
        }
        return true;
    }

    private boolean isRetweet(final Tweet potentialTweet) {
        return potentialTweet.getText().startsWith("RT @");
    }

    /**
     * Determines if a tweet is worth retweeting based on the following criteria: 
     * - number of retweets over a certain threshold (the threshold is per hashtag)
     * - number of favorites (not yet)
     */
    private boolean isTweetWorthRetweetingInContext(final Tweet potentialTweet, final String twitterTag) {
        if (potentialTweet.getRetweetCount() < maxRtRetriever.maxRt(twitterTag)) {
            return false;
        }
        return true;
    }

    /**
     * Determines if the tweet text contains a link
     */
    private final boolean containsLink(final String text) {
        return text.contains("http://");
    }

    private final String preValidityProcess(final String textRaw) {
        return TextUtils.preProcessTweetText(textRaw);
    }

    private final boolean hasThisAlreadyBeenTweeted(final long tweetId) {
        final Retweet existingTweet = retweetApi.findByTweetId(tweetId);
        return existingTweet != null;
    }

    private final void tweet(final String textRaw, final String twitterAccount) {
        final String text = preprocess(textRaw, twitterAccount);

        twitterService.tweet(twitterAccount, text);
    }

    private final String preprocess(final String text, final String twitterAccount) {
        return TwitterUtil.hashtagWords(text, twitterTagsToHash(twitterAccount));
    }

    private final void markTweetRetweeted(final long tweetId, final String twitterAccount) {
        final Retweet retweet = new Retweet(tweetId, twitterAccount);
        retweetApi.save(retweet);
    }

    private final List<String> twitterTagsToHash(final String twitterAccount) {
        final String wordsToHashForAccount = twitterHashtagsRetriever.hashtags(twitterAccount);
        final Iterable<String> split = Splitter.on(',').split(wordsToHashForAccount);
        return Lists.newArrayList(split);
    }

}
