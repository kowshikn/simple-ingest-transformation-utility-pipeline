package com.amazon.dataprepper.plugins.processor.peerforwarder.discovery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;

public class StaticPeerListProvider implements PeerListProvider {
    private static final Logger LOG = LoggerFactory.getLogger(StaticPeerListProvider.class);

    public static final String LOCAL_ENDPOINT = "127.0.0.1";

    private final List<String> endpoints;

    public StaticPeerListProvider(final List<String> dataPrepperEndpoints) {
        if (dataPrepperEndpoints != null && dataPrepperEndpoints.size() > 0) {
            endpoints = Collections.unmodifiableList(dataPrepperEndpoints);
        } else {
            throw new RuntimeException("Peer endpoints list cannot be empty");
        }

        LOG.info("Found endpoints: {}", String.join(",", endpoints));
    }

    @Override
    public List<String> getPeerList() {
        return endpoints;
    }
}
