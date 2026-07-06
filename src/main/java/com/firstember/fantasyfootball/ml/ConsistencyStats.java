package com.firstember.fantasyfootball.ml;

import java.util.List;

public class ConsistencyStats {

    public double std     = 0;
    public double floor   = 0;
    public double ceiling = 0;
    public int gamesOver10 = 0;
    public int gamesOver20 = 0;

    public static ConsistencyStats of(List<Double> pts) {
        ConsistencyStats cs = new ConsistencyStats();
        if (pts.isEmpty()) return cs;

        double mean = pts.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        cs.std = Math.sqrt(pts.stream()
                .mapToDouble(p -> (p - mean) * (p - mean)).average().orElse(0));

        List<Double> sorted = pts.stream().sorted().toList();
        int n    = sorted.size();
        int take = Math.min(4, n);
        cs.floor   = sorted.subList(0, take).stream().mapToDouble(Double::doubleValue).average().orElse(0);
        cs.ceiling = sorted.subList(n - take, n).stream().mapToDouble(Double::doubleValue).average().orElse(0);

        cs.gamesOver10 = (int) pts.stream().filter(p -> p > 10).count();
        cs.gamesOver20 = (int) pts.stream().filter(p -> p > 20).count();
        return cs;
    }
}
