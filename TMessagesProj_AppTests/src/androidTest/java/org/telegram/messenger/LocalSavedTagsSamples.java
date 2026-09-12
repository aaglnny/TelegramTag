package org.telegram.messenger;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.BitmapFactory;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import org.telegram.tgnet.TLRPC;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

class LocalSavedTagsSamples {

    static final String[] TYPES = {"text", "long-text", "webpage", "photo", "video", "file", "voice",
            "music", "gif", "sticker", "round-video", "location", "contact", "poll", "reactions"};

    static TLRPC.MessageMedia media(String type, int id) {
        if (type.equals("photo")) {
            TLRPC.TL_messageMediaPhoto media = new TLRPC.TL_messageMediaPhoto();
            media.photo = new TLRPC.TL_photo();
            media.photo.id = 900000 + id;
            media.photo.date = 1700000000;
            media.photo.dc_id = 1;
            media.photo.file_reference = new byte[0];
            media.photo.sizes.add(thumbnail(id));
            return media;
        }
        if (type.equals("webpage")) {
            TLRPC.TL_messageMediaWebPage media = new TLRPC.TL_messageMediaWebPage();
            TLRPC.TL_webPage page = new TLRPC.TL_webPage();
            page.id = 900000 + id;
            page.url = "https://example.com/";
            page.display_url = "example.com";
            page.type = "article";
            page.site_name = "离线网页样本";
            page.title = "收藏中的网页预览";
            page.description = "核对网页内容、时间和本地标签的位置。";
            media.webpage = page;
            return media;
        }
        if (type.equals("location")) {
            TLRPC.TL_messageMediaGeo media = new TLRPC.TL_messageMediaGeo();
            media.geo = new TLRPC.TL_geoPoint();
            media.geo.lat = 31.2;
            media.geo._long = 121.5;
            return media;
        }
        if (type.equals("contact")) {
            TLRPC.TL_messageMediaContact media = new TLRPC.TL_messageMediaContact();
            media.first_name = "测试联系人";
            media.last_name = "";
            media.phone_number = "0000000000";
            media.vcard = "";
            return media;
        }
        if (type.equals("poll")) {
            TLRPC.TL_messageMediaPoll media = new TLRPC.TL_messageMediaPoll();
            media.poll = new TLRPC.TL_poll();
            media.poll.id = 900000 + id;
            media.poll.question.text = "离线投票样本";
            for (int i = 1; i <= 2; i++) {
                TLRPC.TL_pollAnswer answer = new TLRPC.TL_pollAnswer();
                answer.text.text = "选项 " + i;
                answer.option = new byte[]{(byte) i};
                media.poll.answers.add(answer);
            }
            media.results = new TLRPC.TL_pollResults();
            return media;
        }
        if (type.equals("text") || type.equals("long-text") || type.equals("reactions")) {
            return null;
        }
        TLRPC.TL_messageMediaDocument media = new TLRPC.TL_messageMediaDocument();
        TLRPC.TL_document document = new TLRPC.TL_document();
        document.id = 900000 + id;
        document.dc_id = 1;
        document.date = 1700000000;
        document.file_reference = new byte[0];
        document.size = 4096;
        document.mime_type = "application/pdf";
        TLRPC.TL_documentAttributeFilename filename = new TLRPC.TL_documentAttributeFilename();
        filename.file_name = "本地样本.pdf";
        document.attributes.add(filename);
        if (type.equals("voice") || type.equals("music")) {
            TLRPC.TL_documentAttributeAudio audio = new TLRPC.TL_documentAttributeAudio();
            audio.duration = 5;
            audio.voice = type.equals("voice");
            audio.title = "本地测试音频";
            audio.performer = "离线样本";
            audio.waveform = new byte[]{31, 63, 127, 63, 31, 63, 127, 63, 31, 63};
            document.attributes.add(audio);
            document.mime_type = audio.voice ? "audio/ogg" : "audio/mpeg";
            filename.file_name = audio.voice ? "语音.ogg" : "音乐.mp3";
        } else if (type.equals("video") || type.equals("round-video") || type.equals("gif")) {
            TLRPC.TL_documentAttributeVideo video = new TLRPC.TL_documentAttributeVideo();
            video.w = type.equals("round-video") ? 240 : 480;
            video.h = type.equals("round-video") ? 240 : 320;
            video.duration = 5;
            video.round_message = type.equals("round-video");
            document.attributes.add(video);
            if (type.equals("gif")) {
                document.attributes.add(new TLRPC.TL_documentAttributeAnimated());
            }
            document.mime_type = "video/mp4";
            filename.file_name = "视频.mp4";
            document.thumbs.add(thumbnail(id));
        } else if (type.equals("sticker")) {
            TLRPC.TL_documentAttributeSticker sticker = new TLRPC.TL_documentAttributeSticker();
            sticker.alt = "🙂";
            sticker.stickerset = new TLRPC.TL_inputStickerSetEmpty();
            document.attributes.add(sticker);
            TLRPC.TL_documentAttributeImageSize size = new TLRPC.TL_documentAttributeImageSize();
            size.w = size.h = 256;
            document.attributes.add(size);
            document.mime_type = "image/webp";
            filename.file_name = "贴纸.webp";
            document.thumbs.add(thumbnail(id));
            byte[] bytes = document.thumbs.get(0).bytes;
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            File file = FileLoader.getInstance(0).getPathToAttach(document, true);
            File directory = file.getParentFile();
            if (!directory.isDirectory() && !directory.mkdirs()) {
                throw new IllegalStateException("无法创建离线贴纸目录");
            }
            try (FileOutputStream output = new FileOutputStream(file)) {
                bitmap.compress(Bitmap.CompressFormat.WEBP, 100, output);
            } catch (IOException error) {
                throw new IllegalStateException("无法写入离线贴纸", error);
            } finally {
                bitmap.recycle();
            }
        }
        media.document = document;
        return media;
    }

    private static TLRPC.PhotoSize thumbnail(int id) {
        Bitmap bitmap = Bitmap.createBitmap(240, 160, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.rgb(55, 104 + id % 60, 174));
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.WHITE);
        canvas.drawCircle(175, 50, 24, paint);
        paint.setColor(Color.rgb(45, 151, 127));
        canvas.drawRect(0, 104, 240, 160, paint);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
        bitmap.recycle();
        TLRPC.TL_photoCachedSize size = new TLRPC.TL_photoCachedSize();
        size.type = "x";
        size.w = 480;
        size.h = 320;
        size.bytes = output.toByteArray();
        size.size = size.bytes.length;
        size.location = new TLRPC.TL_fileLocationUnavailable();
        size.location.volume_id = 800000 + id;
        size.location.local_id = id;
        return size;
    }

    static File audioFile(File directory, String name) throws IOException {
        int samples = 16000 * 8;
        ByteBuffer data = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN);
        data.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + samples * 2);
        data.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16);
        data.putShort((short) 1).putShort((short) 1).putInt(16000).putInt(32000);
        data.putShort((short) 2).putShort((short) 16);
        data.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(samples * 2);
        for (int i = 0; i < samples; i++) {
            data.putShort((short) (Math.sin(i * Math.PI * 440 / 8000) * 200));
        }
        File file = new File(directory, name);
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(data.array());
        }
        return file;
    }

    static File videoFile(File directory, String name) throws IOException {
        File file = new File(directory, name);
        MediaCodec encoder = MediaCodec.createEncoderByType("video/avc");
        MediaMuxer muxer = new MediaMuxer(file.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        boolean started = false;
        boolean muxing = false;
        try {
            MediaFormat format = MediaFormat.createVideoFormat("video/avc", 320, 240);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            format.setInteger(MediaFormat.KEY_BIT_RATE, 200000);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 10);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();
            started = true;
            byte[] frame = new byte[320 * 240 * 3 / 2];
            Arrays.fill(frame, 320 * 240, frame.length, (byte) 128);
            int frameIndex = 0;
            int track = -1;
            boolean inputEnded = false;
            boolean outputEnded = false;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long deadline = System.currentTimeMillis() + 30000;
            while (!outputEnded && System.currentTimeMillis() < deadline) {
                if (!inputEnded) {
                    int input = encoder.dequeueInputBuffer(10000);
                    if (input >= 0) {
                        if (frameIndex == 80) {
                            encoder.queueInputBuffer(input, 0, 0, 8000000, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputEnded = true;
                        } else {
                            Arrays.fill(frame, 0, 320 * 240, (byte) 70);
                            int left = frameIndex * 3 % 260;
                            for (int y = 70; y < 130; y++) {
                                Arrays.fill(frame, y * 320 + left, y * 320 + left + 60, (byte) 210);
                            }
                            ByteBuffer buffer = encoder.getInputBuffer(input);
                            buffer.clear();
                            buffer.put(frame);
                            encoder.queueInputBuffer(input, 0, frame.length, frameIndex * 100000L, 0);
                            frameIndex++;
                        }
                    }
                }
                int output = encoder.dequeueOutputBuffer(info, 10000);
                if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(encoder.getOutputFormat());
                    muxer.start();
                    muxing = true;
                } else if (output >= 0) {
                    if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        ByteBuffer buffer = encoder.getOutputBuffer(output);
                        buffer.position(info.offset);
                        buffer.limit(info.offset + info.size);
                        muxer.writeSampleData(track, buffer, info);
                    }
                    outputEnded = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    encoder.releaseOutputBuffer(output, false);
                }
            }
            if (!outputEnded) {
                throw new IOException("本地视频样本编码超时");
            }
        } finally {
            if (started) {
                encoder.stop();
            }
            encoder.release();
            if (muxing) {
                muxer.stop();
            }
            muxer.release();
        }
        return file;
    }
}
