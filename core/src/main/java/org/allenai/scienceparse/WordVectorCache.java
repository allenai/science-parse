package org.allenai.scienceparse;

import org.allenai.word2vec.Searcher;
import org.allenai.word2vec.Word2VecModel;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

public class WordVectorCache {
    private static final Map<Path, Searcher> path2searchers = new TreeMap<Path, Searcher>();

    public static Searcher searcherForPath(final Path path) throws IOException {
        synchronized (path2searchers) {
            Searcher result = path2searchers.get(path);
            if(result != null)
                return result;

            final Word2VecModel word2VecModel = Word2VecModel.fromBinFile(path.toFile());
            result = word2VecModel.forSearch();
            path2searchers.put(path, result);
            return result;
        }
    }
}
