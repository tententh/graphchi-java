package com.twitter.pers.graphchi.walks.distributions;

import com.twitter.pers.graphchi.walks.WalkManager;
import edu.cmu.graphchi.util.IdCount;
import edu.cmu.graphchi.util.IntegerBuffer;

import javax.sound.midi.SysexMessage;
import java.io.*;
import java.rmi.Naming;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.server.UnicastRemoteObject;
import java.util.Arrays;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * DrunkardCompanion is a remote service that receives walks from the walker
 * and maintains a distribution from each source.
 */
public class DrunkardCompanion extends UnicastRemoteObject implements RemoteDrunkardCompanion {

    private static final int BUFFER_CAPACITY = 64;
    private static final int BUFFER_MAX = 256;

    private int maxOutstanding = 4;

    private int[] sourceVertexIds;
    private Object[] locks;
    private DiscreteDistribution[] distributions;
    private IntegerBuffer[] buffers;
    private AtomicInteger outstanding = new AtomicInteger(0);

    private ExecutorService parallelExecutor;


    public DrunkardCompanion() throws RemoteException {
        parallelExecutor = Executors.newFixedThreadPool(4);
    }

    private void mergeWith(int sourceIdx, DiscreteDistribution distr) {
        synchronized (locks[sourceIdx]) {
            distributions[sourceIdx] = DiscreteDistribution.merge(distributions[sourceIdx], distr);
        }
    }

    @Override
    public void setAvoidList(int sourceIdx, int[] avoidList) throws RemoteException {
        Arrays.sort(avoidList);
        DiscreteDistribution avoidDistr = DiscreteDistribution.createAvoidanceDistribution(avoidList);
        mergeWith(sourceIdx, avoidDistr);
    }

    @Override
    public void setSources(int[] sources) throws RemoteException {
        System.out.println("Initializing sources...");
        buffers = new IntegerBuffer[sources.length];
        sourceVertexIds = new int[sources.length];
        locks = new Object[sources.length];
        distributions = new DiscreteDistribution[sources.length];
        for(int i=0; i < sources.length; i++) {
            locks[i] = new Object();
            sourceVertexIds[i] = sources[i];
            buffers[i] = new IntegerBuffer(BUFFER_CAPACITY);
            distributions[i] = DiscreteDistribution.createAvoidanceDistribution(new int[]{sources[i]}); // Add the vertex itself to avoids
        }
        System.out.println("Done...");
    }


    private void _processWalks(int[] walks, int[] atVertices) {
        long t1 = System.currentTimeMillis();
        System.out.println("Processing " + walks.length + " walks...");
        for(int i=0; i < walks.length; i++) {
            int w = walks[i];
            int atVertex = atVertices[i];
            int sourceIdx = WalkManager.sourceIdx(w);

            if (atVertex == sourceVertexIds[sourceIdx]) {
                // Ignore - at origin
                continue;
            }

            IntegerBuffer drainArr = null;
            synchronized (locks[sourceIdx]) {
                buffers[sourceIdx].add(atVertex);
                if (buffers[sourceIdx].size() >= BUFFER_MAX) {
                    drainArr = buffers[sourceIdx];
                    buffers[sourceIdx] = new IntegerBuffer(BUFFER_CAPACITY);
                }
            }

            // Do hard part of the draining outside of lock
            if (drainArr != null) {
                DiscreteDistribution dist = new DiscreteDistribution(drainArr.toIntArray());
                mergeWith(sourceIdx, dist);
            }
        }
        System.out.println("Finished processing, took: " + (System.currentTimeMillis() - t1) + " ms");
    }

    private void drainBuffer(int sourceIdx) {
        int[] arr = buffers[sourceIdx].toIntArray();
        buffers[sourceIdx] = new IntegerBuffer(BUFFER_CAPACITY);
        Arrays.sort(arr);
        DiscreteDistribution dist = new DiscreteDistribution(arr);
        mergeWith(sourceIdx, dist);
    }

    @Override
    public void processWalks(final int[] walks, final int[] atVertices) throws RemoteException {
        while(outstanding.get() > maxOutstanding) {
            System.out.println("Flow control...");
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
            }
        }
        outstanding.incrementAndGet();
        parallelExecutor.submit(new Runnable() {
            @Override
            public void run() {
                try {
                _processWalks(walks, atVertices);
                } finally {
                    outstanding.decrementAndGet();
                }
            }
        });
    }

    @Override
    public void outputDistributions(String outputFile) throws RemoteException {
        System.out.println("Waiting for processing to finish");
        while(outstanding.get() > 0) {
            System.out.println("...");
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        System.out.println("Write output...");
        try {
            BufferedWriter wr = new BufferedWriter(new FileWriter(outputFile));

            for(int i=0; i<sourceVertexIds.length; i++) {
                int sourceVertex = sourceVertexIds[i];
                drainBuffer(i);
                DiscreteDistribution distr = distributions[i];
                TreeSet<IdCount> topVertices = distr.getTop(10);
                wr.write(sourceVertex + "\t");
                for(IdCount vc : topVertices) {
                   wr.write("\t");
                   wr.write(vc.id + "," + vc.count);
                }
                wr.write("\n");
            }
            wr.close();

        } catch (Exception err) {
            err.printStackTrace();
        }
    }

    public static void main(String[] args) throws Exception {
        LocateRegistry.createRegistry(1099);
        Naming.rebind("drunkarcompanion", new DrunkardCompanion());
        System.out.println("Bound to " + Naming.list("dru*")[0]);
    }

}
