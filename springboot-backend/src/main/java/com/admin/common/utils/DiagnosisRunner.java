package com.admin.common.utils;

import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

/** Independent probes run together; preserve their original display order. */
public final class DiagnosisRunner {
    private DiagnosisRunner() {}

    public static <T> List<T> run(List<Callable<T>> probes) {
        if (probes.isEmpty()) return List.of();
        var executor = Executors.newFixedThreadPool(Math.min(8, probes.size()));
        try {
            List<T> results = new ArrayList<>();
            for (var future : executor.invokeAll(probes)) results.add(future.get());
            return results;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("诊断已中断", failure);
        } catch (java.util.concurrent.ExecutionException failure) {
            throw new IllegalStateException("诊断执行失败", failure.getCause());
        } finally {
            executor.shutdownNow();
        }
    }
}
