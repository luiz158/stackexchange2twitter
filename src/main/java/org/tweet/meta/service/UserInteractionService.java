package org.tweet.meta.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.social.twitter.api.Tweet;
import org.springframework.social.twitter.api.TwitterProfile;
import org.springframework.stereotype.Service;
import org.tweet.twitter.service.TweetService;
import org.tweet.twitter.service.live.TwitterReadLiveService;
import org.tweet.twitter.util.TweetUtil;

@Service
public final class UserInteractionService {
    private final Logger logger = LoggerFactory.getLogger(getClass());

    @Autowired
    private TwitterReadLiveService twitterLiveService;

    @Autowired
    private TweetService tweetService;

    public UserInteractionService() {
        super();
    }

    // API

    /**
     * - <b>live</b>: interacts with the twitter API <br/>
     * - <b>local</b>: everything else
     */
    public final boolean isUserWorthInteractingWith(final TwitterProfile user, final String userHandle) {
        final String languageOfUser = user.getLanguage();
        if (!languageOfUser.equals("en")) {
            logger.info("Should not interact with user= {} because user language is= {}", userHandle, languageOfUser);
            return false;
        }
        final int followersCount = user.getFollowersCount();
        if (followersCount < 300) {
            logger.info("Should not interact with user= {} because the followerCount is to small= {}", userHandle, followersCount);
            return false;
        }
        final int tweetsCount = user.getStatusesCount();
        if (tweetsCount < 300) {
            logger.info("Should not interact with user= {} because the tweetsCount is to small= {}", userHandle, tweetsCount);
            return false;
        }

        // final int followingCount = user.getFriendsCount();

        final List<Tweet> tweetsOfAccount = twitterLiveService.listTweetsOfAccountMultiRequestRaw(userHandle, 1);
        final int retweets = countGoodRetweets(tweetsOfAccount);
        final int mentions = countMentions(tweetsOfAccount);
        if (retweets < 6) {
            logger.info("Should not interact with user= {} - the number of retweets (out of the last 200 tweets) is to small= {}", userHandle, retweets);
            return false;
        }
        if (retweets + mentions < 12) {
            logger.info("Should not interact with user= {} - the number of retweets+mentions (out of the last 200 tweets) is to small= {}", userHandle, retweets);
            return false;
        }

        return true;
    }

    public final boolean isUserWorthInteractingWith(final String userHandle) {
        final TwitterProfile user = twitterLiveService.getProfileOfUser(userHandle);
        return isUserWorthInteractingWith(user, userHandle);
    }

    // util

    private boolean isTweetGoodRetweet(final Tweet tweet) {
        if (!tweet.isRetweet()) {
            return false;
        }
        final String text = tweet.getRetweetedStatus().getText();
        if (!tweetService.isTweetWorthRetweetingByText(text)) {
            return false;
        }

        return true;
    }

    private final int countGoodRetweets(final List<Tweet> tweetsOfAccount) {
        int count = 0;
        for (final Tweet tweet : tweetsOfAccount) {
            if (isTweetGoodRetweet(tweet)) {
                count++;
            }
        }
        return count;
    }

    private final int countMentions(final List<Tweet> tweetsOfAccount) {
        int count = 0;
        for (final Tweet tweet : tweetsOfAccount) {
            if (TweetUtil.getText(tweet).contains("@")) {
                count++;
            }
        }
        return count;
    }

}
