/*
 * Copyright @ 2019 8x8, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jitsi.videobridge.cc.vp8;

import net.sf.fmj.media.rtp.*;
import org.jetbrains.annotations.*;
import org.jitsi.impl.neomedia.codec.video.vp8.*;
import org.jitsi.impl.neomedia.rtcp.*;
import org.jitsi.impl.neomedia.rtp.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.util.*;
import org.jitsi.videobridge.cc.*;

import java.util.*;
import java.util.concurrent.*;

/**
 * This class represents a projection of a VP8 RTP stream in the RFC 7667 sense
 * and it is the main entry point for VP8 simulcast/svc RTP/RTCP rewriting. Read
 * svc.md for implementation details. Instances of this class are thread safe.
 *
 * @author George Politis
 */
public class VP8AdaptiveTrackProjectionContext
    implements AdaptiveTrackProjectionContext
{
    /**
     * A map of partially transmitted {@link VP8FrameProjection}s, i.e.
     * projections of VP8 frames for which we haven't transmitted all their
     * packets.
     *
     * Fully transmitted and skipped frames are removed from the map for
     * housekeeping purposes, i.e. to prevent the map from growing too big.
     *
     * The purpose of this map is to enable forwarding and translation of
     * recovered packets of partially transmitted frames and partially
     * transmitted frames _only_.
     *
     * Recovered packets of fully transmitted frames (this can happen for
     * example when the sending endpoint probes for bandwidth with duplicate
     * packets over the RTX stream) are dropped as they're not needed anymore.
     *
     * TODO fine tune the ConcurrentHashMap instance to improve performance.
     */
    private final Map<Long, VP8FrameProjection>
        vp8FrameProjectionMap = new ConcurrentHashMap<>();

    /**
     * The {@link VP8QualityFilter} instance that does quality filtering on the
     * incoming frames.
     */
    private final VP8QualityFilter vp8QualityFilter = new VP8QualityFilter();

    /**
     * The "last" {@link VP8FrameProjection} that this instance has accepted.
     * In this context, last here means with the "highest extended picture id"
     * and not, for example, the last one received by the bridge.
     */
    private VP8FrameProjection lastVP8FrameProjection;

    /**
     *
     */
    private final Object transmittedSyncRoot = new Object();

    /**
     * Keeps track of the number of transmitted bytes. This is used in RTCP SR
     * rewriting.
     */
    private long transmittedBytes = 0;

    /**
     * Keeps track of the number of transmitted packets. This is used in RTCP SR
     * rewriting.
     */
    private long transmittedPackets = 0;

    /**
     * Ctor.
     *
     * @param ssrc the SSRC of the projection.
     */
    public VP8AdaptiveTrackProjectionContext(long ssrc)
    {
        lastVP8FrameProjection = new VP8FrameProjection(ssrc);
    }

    /**
     * Looks-up for an existing VP8 frame projection that corresponds to the
     * specified RTP packet.
     *
     * @param rtpPacket the RTP packet
     * @return an existing VP8 frame projection or null.
     */
    private VP8FrameProjection
    lookupVP8FrameProjection(@NotNull RawPacket rtpPacket)
    {
        // Lookup for an existing VP8 frame doesn't need to be synced because
        // we're using a ConcurrentHashMap. At the time of this writing, two
        // threads reach this point: the translator thread when it decides
        // whether to accept or drop a packet and the transformer thread when it
        // needs to rewrite a packet.

        VP8FrameProjection
            lastVP8FrameProjectionCopy = lastVP8FrameProjection;

        // First, check if this is a packet from the "last" VP8 frame.
        VP8Frame lastVP8Frame = lastVP8FrameProjectionCopy.getVP8Frame();

        // XXX we must check for null because the initial projection does not
        // have an associated frame.
        if (lastVP8Frame != null && lastVP8Frame.matchesFrame(rtpPacket))
        {
            return lastVP8FrameProjectionCopy;
        }

        // Check if this is a packet from a partially transmitted frame
        // (partially transmitted implies that the frame has been previously
        // accepted; the inverse does not necessarily hold).

        VP8FrameProjection cachedVP8FrameProjection
            = vp8FrameProjectionMap.get(rtpPacket.getTimestamp());

        if (cachedVP8FrameProjection != null)
        {
            VP8Frame cachedVP8Frame = cachedVP8FrameProjection.getVP8Frame();

            // XXX we match both the pkt timestamp *and* the pkt SSRC, as the
            // vp8FrameProjection may refer to a frame from another RTP stream.
            // In that case, we want to skip the return statement below.
            if (cachedVP8Frame != null && cachedVP8Frame.matchesFrame(rtpPacket))
            {
                return cachedVP8FrameProjection;
            }
        }

        return null;
    }

    /**
     * Defines a packet filter that determines which packets to project in order
     * to produce an RTP stream that can be correctly be decoded at the receiver
     * as well as match, as close as possible, the changing quality target.
     *
     * @param rtpPacket the VP8 packet to decide whether or not to project.
     * @return true to project the packet, otherwise false.
     */
    private synchronized
    VP8FrameProjection createVP8FrameProjection(
        @NotNull RawPacket rtpPacket, int targetIndex)
    {
        // Creating a new VP8 projection depends on reading and results in
        // writing of the last VP8 frame, therefore this method needs to be
        // synced. At the time of this writing, only the translator thread is
        // reaches this point.

        VP8Frame lastVP8Frame = lastVP8FrameProjection.getVP8Frame();
        // Old VP8 frames cannot be accepted because there's no "free" space in
        // the sequence numbers. Check that before we create any structures to
        // support the incoming packet/frame.
        if (lastVP8Frame != null && lastVP8Frame.matchesOlderFrame(rtpPacket))
        {
            return null;
        }

        // if packet loss/re-ordering happened and this is not the first packet
        // of a frame, then we don't process it right now. It'll get its chance
        // when the first packet arrives and, if it's chosen for forwarding,
        // we'll piggy-back any missed packets.
        byte[] buf = rtpPacket.getBuffer();
        int payloadOff = rtpPacket.getPayloadOffset();
        if (!DePacketizer.VP8PayloadDescriptor.isStartOfFrame(buf, payloadOff))
        {
            return null;
        }

        // Lastly, check whether the quality of the frame is something that we
        // want to forward. We don't want to be allocating new objects unless
        // we're interested in the quality of this frame.
        if (!vp8QualityFilter.acceptFrame(rtpPacket, targetIndex))
        {
            return null;
        }

        long nowMs = System.currentTimeMillis();

        // We know we want to forward this frame, but we need to make sure it's
        // going to produce a decodable VP8 packet stream.
        VP8FrameProjection nextVP8FrameProjection
            = lastVP8FrameProjection.makeNext(rtpPacket, nowMs);
        if (nextVP8FrameProjection == null)
        {
            return null;
        }

        // We have successfully projected the incoming frame and we've allocated
        // a starting sequence number for it. Any previous frames can no longer
        // grow.
        vp8FrameProjectionMap.put(rtpPacket.getTimestamp(), nextVP8FrameProjection);
        // The frame attached to the "last" projection is no longer the "last".
        lastVP8FrameProjection = nextVP8FrameProjection;

        // Cleanup the frame projection map.
        vp8FrameProjectionMap.entrySet().removeIf(
            e -> e.getValue().isFullyProjected(nowMs));

        return nextVP8FrameProjection;
    }

    /**
     * @return true if this instance needs a keyframe, false otherwise.
     */
    @Override
    public boolean needsKeyframe()
    {
        if (vp8QualityFilter.needsKeyframe())
        {
            return true;
        }

        VP8Frame lastVP8Frame = lastVP8FrameProjection.getVP8Frame();
        return lastVP8Frame == null || lastVP8Frame.needsKeyframe();
    }

    /**
     * Determines whether a packet should be accepted or not.
     *
     * @param rtpPacket the RTP packet to determine whether to project or not.
     * @param targetIndex the target index to achieve
     * @return true if the packet should be accepted, false otherwise.
     */
    @Override
    public boolean accept(@NotNull RawPacket rtpPacket, int targetIndex)
    {
        VP8FrameProjection vp8FrameProjection
            = lookupVP8FrameProjection(rtpPacket);

        if (vp8FrameProjection == null)
        {
            vp8FrameProjection = createVP8FrameProjection(rtpPacket, targetIndex);
        }

        return vp8FrameProjection != null
            && vp8FrameProjection.accept(rtpPacket);
    }

    /**
     * Rewrites the RTCP packet that is specified as an argument.
     *
     * @param rtcpPacket the RTCP packet to transform.
     * @return true if the RTCP packet is accepted, false otherwise, in which
     * case it needs to be dropped.
     */
    @Override
    public boolean rewriteRtcp(@NotNull RawPacket rtcpPacket)
    {
        // Drop SRs from other streams.
        boolean removed = false;
        RTCPIterator it = new RTCPIterator(rtcpPacket);
        while (it.hasNext())
        {
            ByteArrayBuffer baf = it.next();
            switch (RTCPUtils.getPacketType(baf))
            {
            case RTCPPacket.SDES:
                if (removed)
                {
                    it.remove();
                }
                break;
            case RTCPPacket.SR:
                VP8FrameProjection
                    lastVP8FrameProjectionCopy = lastVP8FrameProjection;
                if (lastVP8FrameProjectionCopy.getVP8Frame() == null
                    || RawPacket.getRTCPSSRC(baf)
                    != lastVP8FrameProjectionCopy.getSSRC())
                {
                    // SRs from other streams get axed.
                    removed = true;
                    it.remove();
                }
                else
                {
                    long srcTs = RTCPSenderInfoUtils.getTimestamp(baf);
                    long delta = RTPUtils.rtpTimestampDiff(
                        lastVP8FrameProjectionCopy.getTimestamp(),
                        lastVP8FrameProjectionCopy.getVP8Frame().getTimestamp());

                    long dstTs = RTPUtils.as32Bits(srcTs + delta);

                    if (srcTs != dstTs)
                    {
                        RTCPSenderInfoUtils.setTimestamp(baf, (int) dstTs);
                    }

                    // Rewrite packet/octet count.
                    synchronized (transmittedSyncRoot)
                    {
                        RTCPSenderInfoUtils
                            .setOctetCount(baf, (int) transmittedBytes);
                        RTCPSenderInfoUtils
                            .setPacketCount(baf, (int) transmittedPackets);
                    }
                }
                break;
            case RTCPPacket.BYE:
                // TODO rewrite SSRC.
                break;
            }
        }

        return rtcpPacket.getLength() > 0;
    }

    /**
     * Rewrites the RTP packet that is specified as an argument.
     *
     * @param rtpPacket the RTP packet to rewrite.
     * @param incomingRawPacketCache the packet cache to pull piggy-backed
     * packets from. Null is permissible, but in that case no packets will be
     * piggy backed.
     * @return any RTP packets to piggy-bac
     * @throws RewriteException if a VP8 frame projection is not found
     * for the RTP packet that is specified as a parameter.
     */
    @Override
    public RawPacket[] rewriteRtp(
        @NotNull RawPacket rtpPacket, RawPacketCache incomingRawPacketCache)
        throws RewriteException
    {
        VP8FrameProjection vp8FrameProjection
            = lookupVP8FrameProjection(rtpPacket);
        if (vp8FrameProjection == null)
        {
            // This packet does not belong to a projected frame.
            throw new RewriteException();
        }

        RawPacket[] ret
            = vp8FrameProjection.rewriteRtp(rtpPacket, incomingRawPacketCache);

        synchronized (transmittedSyncRoot)
        {
            transmittedBytes += rtpPacket.getLength();
            transmittedPackets++;

            if (!ArrayUtils.isNullOrEmpty(ret))
            {
                for (int i = 0; i < ret.length; i++)
                {
                    transmittedBytes += ret[i].getLength();
                    transmittedPackets += 1;
                }
            }
        }

        return ret;
    }
}