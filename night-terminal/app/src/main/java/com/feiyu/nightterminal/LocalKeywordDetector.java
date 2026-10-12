package com.feiyu.nightterminal;

import android.content.res.AssetManager;
import com.k2fsa.sherpa.onnx.KeywordSpotter;
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;

final class LocalKeywordDetector implements AutoCloseable {
    private final KeywordSpotter spotter;
    private final OnlineStream stream;

    LocalKeywordDetector(AssetManager assets) {
        OnlineModelConfig model = new OnlineModelConfig();
        model.setTransducer(new OnlineTransducerModelConfig("kws/encoder.int8.onnx",
                "kws/decoder.int8.onnx", "kws/joiner.int8.onnx"));
        model.setTokens("kws/tokens.txt");
        model.setNumThreads(1);
        model.setModelType("zipformer2");
        KeywordSpotterConfig config = new KeywordSpotterConfig();
        config.setModelConfig(model);
        config.setKeywordsFile("kws/keywords.txt");
        config.setKeywordsScore(1.5f);
        config.setKeywordsThreshold(0.25f);
        config.setNumTrailingBlanks(2);
        spotter = new KeywordSpotter(assets, config);
        stream = spotter.createStream("");
    }

    boolean accept(float[] samples) {
        stream.acceptWaveform(samples, 16000);
        while (spotter.isReady(stream)) {
            spotter.decode(stream);
            if ("大肥鱼".equals(spotter.getResult(stream).getKeyword())) {
                spotter.reset(stream);
                return true;
            }
        }
        return false;
    }

    @Override public void close() { stream.release(); spotter.release(); }
}
