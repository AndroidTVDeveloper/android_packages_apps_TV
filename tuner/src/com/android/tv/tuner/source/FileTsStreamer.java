/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.tv.tuner.source;

import android.content.Context;
import android.os.Environment;
import android.util.Log;
import android.util.SparseBooleanArray;
import com.android.tv.common.SoftPreconditions;
import com.android.tv.tuner.ChannelScanFileParser.ScanChannel;
import com.android.tv.tuner.data.TunerChannel;
import com.android.tv.tuner.features.TunerFeatures;
import com.android.tv.tuner.ts.TsParser;
import com.android.tv.tuner.tvinput.EventDetector;
import com.android.tv.tuner.tvinput.FileSourceEventDetector;
import com.google.android.exoplayer.C;
import com.google.android.exoplayer.upstream.DataSpec;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Provides MPEG-2 TS stream sources for both channel scanning and channel playing from a local file
 * generated by capturing TV signal.
 */
public class FileTsStreamer implements TsStreamer {
    private static final String TAG = "FileTsStreamer";

    private static final int TS_PACKET_SIZE = 188;
    private static final int TS_SYNC_BYTE = 0x47;
    private static final int MIN_READ_UNIT = TS_PACKET_SIZE * 10;
    private static final int READ_BUFFER_SIZE = MIN_READ_UNIT * 10; // ~20KB
    private static final int CIRCULAR_BUFFER_SIZE = MIN_READ_UNIT * 4000; // ~ 8MB
    private static final int PADDING_SIZE = MIN_READ_UNIT * 1000; // ~2MB
    private static final int READ_TIMEOUT_MS = 10000; // 10 secs.
    private static final int BUFFER_UNDERRUN_SLEEP_MS = 10;
    private static final String FILE_DIR =
            new File(Environment.getExternalStorageDirectory(), "Streams").getAbsolutePath();

    // Virtual frequency base used for file-based source
    public static final int FREQ_BASE = 100;

    private final Object mCircularBufferMonitor = new Object();
    private final byte[] mCircularBuffer = new byte[CIRCULAR_BUFFER_SIZE];
    private final FileSourceEventDetector mEventDetector;
    private final Context mContext;

    private long mBytesFetched;
    private long mLastReadPosition;
    private boolean mStreaming;

    private Thread mStreamingThread;
    private StreamProvider mSource;

    public static class FileDataSource extends TsDataSource {
        private final FileTsStreamer mTsStreamer;
        private final AtomicLong mLastReadPosition = new AtomicLong(0);
        private long mStartBufferedPosition;

        private FileDataSource(FileTsStreamer tsStreamer) {
            mTsStreamer = tsStreamer;
            mStartBufferedPosition = tsStreamer.getBufferedPosition();
        }

        @Override
        public long getBufferedPosition() {
            return mTsStreamer.getBufferedPosition() - mStartBufferedPosition;
        }

        @Override
        public long getLastReadPosition() {
            return mLastReadPosition.get();
        }

        @Override
        public void shiftStartPosition(long offset) {
            SoftPreconditions.checkState(mLastReadPosition.get() == 0);
            SoftPreconditions.checkArgument(0 <= offset && offset <= getBufferedPosition());
            mStartBufferedPosition += offset;
        }

        @Override
        public long open(DataSpec dataSpec) throws IOException {
            mLastReadPosition.set(0);
            return C.LENGTH_UNBOUNDED;
        }

        @Override
        public void close() {}

        @Override
        public int read(byte[] buffer, int offset, int readLength) throws IOException {
            int ret =
                    mTsStreamer.readAt(
                            mStartBufferedPosition + mLastReadPosition.get(),
                            buffer,
                            offset,
                            readLength);
            if (ret > 0) {
                mLastReadPosition.addAndGet(ret);
            }
            return ret;
        }
    }

    /**
     * Creates {@link TsStreamer} for scanning & playing MPEG-2 TS file.
     *
     * @param eventListener the listener for channel & program information
     */
    public FileTsStreamer(EventDetector.EventListener eventListener, Context context) {
        mEventDetector =
                new FileSourceEventDetector(
                        eventListener, TunerFeatures.ENABLE_FILE_DVB.isEnabled(context));
        mContext = context;
    }

    @Override
    public boolean startStream(ScanChannel channel) {
        String filepath = new File(FILE_DIR, channel.filename).getAbsolutePath();
        mSource = new StreamProvider(filepath);
        if (!mSource.isReady()) {
            return false;
        }
        mEventDetector.start(mSource, FileSourceEventDetector.ALL_PROGRAM_NUMBERS);
        mSource.addPidFilter(TsParser.PAT_PID);
        mSource.addPidFilter(TsParser.ATSC_SI_BASE_PID);
        if (TunerFeatures.ENABLE_FILE_DVB.isEnabled(mContext)) {
            mSource.addPidFilter(TsParser.DVB_EIT_PID);
            mSource.addPidFilter(TsParser.DVB_SDT_PID);
        }
        synchronized (mCircularBufferMonitor) {
            if (mStreaming) {
                return true;
            }
            mStreaming = true;
        }

        mStreamingThread = new StreamingThread();
        mStreamingThread.start();
        Log.i(TAG, "Streaming started");
        return true;
    }

    @Override
    public boolean startStream(TunerChannel channel) {
        Log.i(TAG, "tuneToChannel with: " + channel.getFilepath());
        mSource = new StreamProvider(channel.getFilepath());
        if (!mSource.isReady()) {
            return false;
        }
        mEventDetector.start(mSource, channel.getProgramNumber());
        mSource.addPidFilter(channel.getVideoPid());
        for (Integer i : channel.getAudioPids()) {
            mSource.addPidFilter(i);
        }
        mSource.addPidFilter(channel.getPcrPid());
        mSource.addPidFilter(TsParser.PAT_PID);
        mSource.addPidFilter(TsParser.ATSC_SI_BASE_PID);
        if (TunerFeatures.ENABLE_FILE_DVB.isEnabled(mContext)) {
            mSource.addPidFilter(TsParser.DVB_EIT_PID);
            mSource.addPidFilter(TsParser.DVB_SDT_PID);
        }
        synchronized (mCircularBufferMonitor) {
            if (mStreaming) {
                return true;
            }
            mStreaming = true;
        }

        mStreamingThread = new StreamingThread();
        mStreamingThread.start();
        Log.i(TAG, "Streaming started");
        return true;
    }

    /**
     * Blocks the current thread until the streaming thread stops. In rare cases when the tuner
     * device is overloaded this can take a while, but usually it returns pretty quickly.
     */
    @Override
    public void stopStream() {
        synchronized (mCircularBufferMonitor) {
            mStreaming = false;
            mCircularBufferMonitor.notify();
        }

        try {
            if (mStreamingThread != null) {
                mStreamingThread.join();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public TsDataSource createDataSource() {
        return new FileDataSource(this);
    }

    /**
     * Returns the current buffered position from the file.
     *
     * @return the current buffered position
     */
    public long getBufferedPosition() {
        synchronized (mCircularBufferMonitor) {
            return mBytesFetched;
        }
    }

    /** Provides MPEG-2 transport stream from a local file. Stream can be filtered by PID. */
    public static class StreamProvider {
        private final String mFilepath;
        private final SparseBooleanArray mPids = new SparseBooleanArray();
        private final byte[] mPreBuffer = new byte[READ_BUFFER_SIZE];

        private BufferedInputStream mInputStream;

        private StreamProvider(String filepath) {
            mFilepath = filepath;
            open(filepath);
        }

        private void open(String filepath) {
            try {
                mInputStream = new BufferedInputStream(new FileInputStream(filepath));
            } catch (IOException e) {
                Log.e(TAG, "Error opening input stream", e);
                mInputStream = null;
            }
        }

        private boolean isReady() {
            return mInputStream != null;
        }

        /** Returns the file path of the MPEG-2 TS file. */
        public String getFilepath() {
            return mFilepath;
        }

        /** Adds a pid for filtering from the MPEG-2 TS file. */
        public void addPidFilter(int pid) {
            mPids.put(pid, true);
        }

        /** Returns whether the current pid filter is empty or not. */
        public boolean isFilterEmpty() {
            return mPids.size() == 0;
        }

        /** Clears the current pid filter. */
        public void clearPidFilter() {
            mPids.clear();
        }

        /**
         * Returns whether a pid is in the pid filter or not.
         *
         * @param pid the pid to check
         */
        public boolean isInFilter(int pid) {
            return mPids.get(pid);
        }

        /**
         * Reads from the MPEG-2 TS file to buffer.
         *
         * @param inputBuffer to read
         * @return the number of read bytes
         */
        private int read(byte[] inputBuffer) {
            int readSize = readInternal();
            if (readSize <= 0) {
                // Reached the end of stream. Restart from the beginning.
                close();
                open(mFilepath);
                if (mInputStream == null) {
                    return -1;
                }
                readSize = readInternal();
            }

            if (mPreBuffer[0] != TS_SYNC_BYTE) {
                Log.e(TAG, "Error reading input stream - no TS sync found");
                return -1;
            }
            int filteredSize = 0;
            for (int i = 0, destPos = 0; i < readSize; i += TS_PACKET_SIZE) {
                if (mPreBuffer[i] == TS_SYNC_BYTE) {
                    int pid = ((mPreBuffer[i + 1] & 0x1f) << 8) + (mPreBuffer[i + 2] & 0xff);
                    if (mPids.get(pid)) {
                        System.arraycopy(mPreBuffer, i, inputBuffer, destPos, TS_PACKET_SIZE);
                        destPos += TS_PACKET_SIZE;
                        filteredSize += TS_PACKET_SIZE;
                    }
                }
            }
            return filteredSize;
        }

        private int readInternal() {
            int readSize;
            try {
                readSize = mInputStream.read(mPreBuffer, 0, mPreBuffer.length);
            } catch (IOException e) {
                Log.e(TAG, "Error reading input stream", e);
                return -1;
            }
            return readSize;
        }

        private void close() {
            try {
                mInputStream.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing input stream:", e);
            }
            mInputStream = null;
        }
    }

    /**
     * Reads data from internal buffer.
     *
     * @param pos the position to read from
     * @param buffer to read
     * @param offset start position of the read buffer
     * @param amount number of bytes to read
     * @return number of read bytes when successful, {@code -1} otherwise
     * @throws IOException
     */
    public int readAt(long pos, byte[] buffer, int offset, int amount) throws IOException {
        synchronized (mCircularBufferMonitor) {
            long initialBytesFetched = mBytesFetched;
            while (mBytesFetched < pos + amount && mStreaming) {
                try {
                    mCircularBufferMonitor.wait(READ_TIMEOUT_MS);
                } catch (InterruptedException e) {
                    // Wait again.
                    Thread.currentThread().interrupt();
                }
                if (initialBytesFetched == mBytesFetched) {
                    Log.w(TAG, "No data update for " + READ_TIMEOUT_MS + "ms. returning -1.");

                    // Returning -1 will make demux report EOS so that the input service can retry
                    // the playback.
                    return -1;
                }
            }
            if (!mStreaming) {
                Log.w(TAG, "Stream is already stopped.");
                return -1;
            }
            if (mBytesFetched - CIRCULAR_BUFFER_SIZE > pos) {
                Log.e(TAG, "Demux is requesting the data which is already overwritten.");
                return -1;
            }
            int posInBuffer = (int) (pos % CIRCULAR_BUFFER_SIZE);
            int bytesToCopyInFirstPass = amount;
            if (posInBuffer + bytesToCopyInFirstPass > mCircularBuffer.length) {
                bytesToCopyInFirstPass = mCircularBuffer.length - posInBuffer;
            }
            System.arraycopy(mCircularBuffer, posInBuffer, buffer, offset, bytesToCopyInFirstPass);
            if (bytesToCopyInFirstPass < amount) {
                System.arraycopy(
                        mCircularBuffer,
                        0,
                        buffer,
                        offset + bytesToCopyInFirstPass,
                        amount - bytesToCopyInFirstPass);
            }
            mLastReadPosition = pos + amount;
            mCircularBufferMonitor.notify();
            return amount;
        }
    }

    /**
     * Adds {@link ScanChannel} instance for local files.
     *
     * @param output a list of channels where the results will be placed in
     */
    public static void addLocalStreamFiles(List<ScanChannel> output) {
        File dir = new File(FILE_DIR);
        if (!dir.exists()) return;

        File[] tsFiles = dir.listFiles();
        if (tsFiles == null) return;
        int freq = FileTsStreamer.FREQ_BASE;
        for (File file : tsFiles) {
            if (!file.isFile()) continue;
            output.add(ScanChannel.forFile(freq, file.getName()));
            freq += 100;
        }
    }

    /**
     * A thread managing a circular buffer that holds stream data to be consumed by player. Keeps
     * reading data in from a {@link StreamProvider} to hold enough amount for buffering. Started
     * and stopped by {@link #startStream()} and {@link #stopStream()}, respectively.
     */
    private class StreamingThread extends Thread {
        @Override
        public void run() {
            byte[] dataBuffer = new byte[READ_BUFFER_SIZE];

            synchronized (mCircularBufferMonitor) {
                mBytesFetched = 0;
                mLastReadPosition = 0;
            }

            while (true) {
                synchronized (mCircularBufferMonitor) {
                    while ((mBytesFetched - mLastReadPosition + PADDING_SIZE) > CIRCULAR_BUFFER_SIZE
                            && mStreaming) {
                        try {
                            mCircularBufferMonitor.wait();
                        } catch (InterruptedException e) {
                            // Wait again.
                            Thread.currentThread().interrupt();
                        }
                    }
                    if (!mStreaming) {
                        break;
                    }
                }

                int bytesWritten = mSource.read(dataBuffer);
                if (bytesWritten <= 0) {
                    try {
                        // When buffer is underrun, we sleep for short time to prevent
                        // unnecessary CPU draining.
                        sleep(BUFFER_UNDERRUN_SLEEP_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    continue;
                }

                mEventDetector.feedTSStream(dataBuffer, 0, bytesWritten);

                synchronized (mCircularBufferMonitor) {
                    int posInBuffer = (int) (mBytesFetched % CIRCULAR_BUFFER_SIZE);
                    int bytesToCopyInFirstPass = bytesWritten;
                    if (posInBuffer + bytesToCopyInFirstPass > mCircularBuffer.length) {
                        bytesToCopyInFirstPass = mCircularBuffer.length - posInBuffer;
                    }
                    System.arraycopy(
                            dataBuffer, 0, mCircularBuffer, posInBuffer, bytesToCopyInFirstPass);
                    if (bytesToCopyInFirstPass < bytesWritten) {
                        System.arraycopy(
                                dataBuffer,
                                bytesToCopyInFirstPass,
                                mCircularBuffer,
                                0,
                                bytesWritten - bytesToCopyInFirstPass);
                    }
                    mBytesFetched += bytesWritten;
                    mCircularBufferMonitor.notify();
                }
            }

            Log.i(TAG, "Streaming stopped");
            mSource.close();
        }
    }
}
