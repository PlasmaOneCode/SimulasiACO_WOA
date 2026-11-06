package org.cloudbus.cloudsim.examples;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.CloudletSchedulerSpaceShared;
import org.cloudbus.cloudsim.Datacenter;
import org.cloudbus.cloudsim.DatacenterBroker;
import org.cloudbus.cloudsim.DatacenterCharacteristics;
import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.Pe;
import org.cloudbus.cloudsim.Storage;
import org.cloudbus.cloudsim.UtilizationModel;
import org.cloudbus.cloudsim.UtilizationModelFull;
import org.cloudbus.cloudsim.Vm;
import org.cloudbus.cloudsim.VmAllocationPolicySimple;
import org.cloudbus.cloudsim.VmSchedulerTimeShared;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.provisioners.BwProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.PeProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.RamProvisionerSimple;

/**
 * WOA_TaskScheduling: Whale Optimization Algorithm task scheduling using CloudSim.
 *
 * Scenario:
 * 1 Datacenter
 * 10 heterogeneous Hosts
 * 20 heterogeneous VMs
 * N Cloudlets read from dataset/GoCJ_Dataset_N.txt (GoCJ file of that size)
 *
 * WOA searches for a Cloudlet-to-VM mapping that minimizes the makespan.
 * 30 whales x 100 iterations = 3000 fitness evaluations.
 *
 * The resulting assignment is bound through DatacenterBroker (setGuestId)
 * before the simulation, exactly like the MET reference implementation.
 *
 * Usage (run from the repository root):
 *   WOA_TaskScheduling [numTasks] [run] [seed] [outputCsv] [datasetPath]
 * Defaults: 1000 tasks, run 1, seed = run, results/woa_manual.csv,
 *           dataset/GoCJ_Dataset_<numTasks>.txt
 */
public class WOA_TaskScheduling {

    private static final int NUM_USERS = 1;
    private static final int NUM_HOSTS = 10;
    private static final int NUM_VMS = 20;

    /* Dataset GoCJ per ukuran: dataset/GoCJ_Dataset_<jumlahTask>.txt */
    private static String defaultDatasetPath(int numTasks) {
        return "dataset/GoCJ_Dataset_" + numTasks + ".txt";
    }
    private static final String DEFAULT_CSV = "results/woa_manual.csv";
    private static final String FAMILY = "GoCJ";

    /* Locale.ROOT agar titik desimal tetap '.' di komputer berlokal Indonesia */
    private static final DecimalFormat DF =
            new DecimalFormat("0.####", DecimalFormatSymbols.getInstance(Locale.ROOT));

    /* ---------- Parameter WOA ---------- */
    private static final int WHALES = 30;
    private static final int ITERATIONS = 100;       // 30 x 100 = 3000 evaluasi
    private static final double B = 1.0;             // konstanta bentuk spiral


    public static void main(String[] args) {
        int numTasks = args.length > 0 ? Integer.parseInt(args[0]) : 1000;
        int run = args.length > 1 ? Integer.parseInt(args[1]) : 1;
        long seed = args.length > 2 ? Long.parseLong(args[2]) : run;
        String csvPath = args.length > 3 ? args[3] : DEFAULT_CSV;
        String datasetPath = args.length > 4 ? args[4] : defaultDatasetPath(numTasks);

        Log.println("Starting WOA_TaskScheduling...");
        try {
            /* Membuat datacenter dan broker */
            CloudSim.init(NUM_USERS, Calendar.getInstance(), false);

            Datacenter datacenter = createDatacenter("Datacenter_0");
            DatacenterBroker broker = new DatacenterBroker("WOA_Broker");
            int brokerId = broker.getId();

            /* Menyiapkan VM dan membaca N panjang pekerjaan pertama */
            List<Vm> vmList = createVmList(brokerId);

            List<Long> jobLengths = loadGoCJ(Paths.get(datasetPath));
            Log.println("Dataset loaded: " + datasetPath
                    + " (" + jobLengths.size() + " jobs available)");

            /* File per ukuran harus berisi tepat numTasks job; jika memakai
               file yang lebih besar (argumen dataset), diambil N job pertama. */
            if (numTasks < 1 || numTasks > jobLengths.size()) {
                throw new IllegalArgumentException(
                    "Dataset " + datasetPath + " has " + jobLengths.size()
                    + " jobs but numTasks was " + numTasks);
            }
            jobLengths = new ArrayList<>(jobLengths.subList(0, numTasks));

            List<Cloudlet> cloudletList =
                    createCloudletList(brokerId, jobLengths);

            broker.submitGuestList(vmList);

            /* Menjalankan algoritma metaheuristik untuk menentukan VM tiap Cloudlet */
            VmModel model = new VmModel(vmList);
            double[] length = new double[jobLengths.size()];
            for (int i = 0; i < length.length; i++) {
                length[i] = jobLengths.get(i);
            }

            Random rnd = new Random(seed);
            long startNs = System.nanoTime();
            Solution solution = runWOA(length, model, rnd);
            double schedulingMs = (System.nanoTime() - startNs) / 1_000_000.0;

            Map<Integer, Integer> assignment = new LinkedHashMap<>();
            for (int i = 0; i < cloudletList.size(); i++) {
                Cloudlet cloudlet = cloudletList.get(i);
                int vmId = vmList.get(solution.assign[i]).getId();

                // Sama seperti MET: Cloudlet diikat ke VM via GuestId
                // sebelum daftar Cloudlet dikirim ke Broker.
                cloudlet.setGuestId(vmId);
                assignment.put(cloudlet.getCloudletId(), vmId);
            }

            broker.submitCloudletList(cloudletList);

            printConfiguration(jobLengths, vmList, assignment,
                    run, seed, solution, schedulingMs);

            /* Menjalankan simulasi dan mengambil hasilnya */
            CloudSim.startSimulation();
            CloudSim.stopSimulation();

            List<Cloudlet> results = broker.getCloudletReceivedList();

            printCloudletResults(results);
            double[] metrics = printMetrics(results, vmList);

            appendCsv(Paths.get(csvPath), numTasks, run, seed,
                    results.size(), metrics, solution.makespan, schedulingMs);

            Log.println("WOA_TaskScheduling finished!");

        } catch (Exception e) {
            e.printStackTrace();
            Log.println("Simulation failed.");
            System.exit(1);
        }
    }

    /* ====================================================================== */
    /* Algoritma                                                              */
    /* ====================================================================== */

    /** Hasil penjadwalan: indeks VM untuk setiap Cloudlet dan nilai fitness. */
    private static final class Solution {
        final int[] assign;
        final double fitness;   // makespan + tie-break (yang diminimalkan)
        final double makespan;  // estimasi makespan murni

        Solution(int[] assign, double fitness, double makespan) {
            this.assign = assign;
            this.fitness = fitness;
            this.makespan = makespan;
        }
    }

    /**
     * Ringkasan spesifikasi VM untuk menghitung fitness tanpa memanggil
     * CloudSim. Setiap Cloudlet memakai 1 PE; VM dengan p PE menjalankan
     * maksimal p Cloudlet paralel (CloudletSchedulerSpaceShared) dengan
     * antrean FIFO sesuai urutan id Cloudlet.
     */
    private static final class VmModel {
        final int count;
        final double[] mips;
        final int[] pes;
        final int[] offset;
        final int totalPes;

        VmModel(List<Vm> vmList) {
            count = vmList.size();
            mips = new double[count];
            pes = new int[count];
            offset = new int[count];

            int sum = 0;
            for (int j = 0; j < count; j++) {
                Vm vm = vmList.get(j);
                mips[j] = vm.getMips();
                pes[j] = vm.getNumberOfPes();
                offset[j] = sum;
                sum += pes[j];
            }
            totalPes = sum;
        }

        /**
         * Bobot tie-breaker. Makespan tetap tujuan utama; rata-rata waktu
         * selesai (bobot 0,1%) hanya membedakan solusi yang makespan-nya
         * sama, misalnya saat satu Cloudlet terbesar sudah menentukan
         * makespan pada jumlah task kecil.
         */
        static final double TIE_BREAK = 0.001;

        private double lastMakespan;

        /** Simulasi antrean per VM; mengembalikan jumlah waktu selesai. */
        private double simulate(int[] assign, double[] length, double[] peFree) {
            Arrays.fill(peFree, 0.0);
            double max = 0.0;
            double sumFinish = 0.0;

            for (int i = 0; i < assign.length; i++) {
                int j = assign[i];
                int from = offset[j];
                int to = from + pes[j];

                int k = from;
                for (int q = from + 1; q < to; q++) {
                    if (peFree[q] < peFree[k]) {
                        k = q;
                    }
                }

                double finish = peFree[k] + length[i] / mips[j];
                peFree[k] = finish;
                sumFinish += finish;
                if (finish > max) {
                    max = finish;
                }
            }

            lastMakespan = max;
            return sumFinish;
        }

        /** Fitness yang diminimalkan: makespan + TIE_BREAK x rata-rata selesai. */
        double fitness(int[] assign, double[] length, double[] peFree) {
            double sumFinish = simulate(assign, length, peFree);
            return lastMakespan + TIE_BREAK * sumFinish / assign.length;
        }

        /** Estimasi makespan murni untuk satu solusi. */
        double makespan(int[] assign, double[] length, double[] peFree) {
            simulate(assign, length, peFree);
            return lastMakespan;
        }
    }

    /* ====================================================================== */
    /* WOA (Whale Optimization Algorithm)                                     */
    /* ====================================================================== */

    private static String parameterSummary() {
        return "whales=" + WHALES + ", iterations=" + ITERATIONS
                + ", b=" + B;
    }

    /**
     * Setiap paus adalah vektor posisi kontinu berdimensi N (satu elemen per
     * Cloudlet) dengan nilai pada [0, jumlah VM). Posisi didekode menjadi
     * indeks VM dengan floor(). Tiga perilaku WOA standar (Mirjalili & Lewis,
     * 2016) dipakai:
     *   - |A| < 1 : mengepung mangsa (mendekati posisi terbaik),
     *   - |A| >= 1: mencari mangsa (mengikuti paus acak),
     *   - p >= 0.5: gerakan spiral mengitari posisi terbaik.
     * Iterasi pertama mengevaluasi populasi awal, sehingga total evaluasi
     * fitness = WHALES x ITERATIONS (sama dengan ACO).
     */
    private static Solution runWOA(double[] length, VmModel model, Random rnd) {
        int n = length.length;
        int m = model.count;
        double upper = m - 1e-9;

        double[][] pos = new double[WHALES][n];
        for (double[] row : pos) {
            for (int d = 0; d < n; d++) {
                row[d] = rnd.nextDouble() * m;
            }
        }

        double[] leader = new double[n];
        int[] leaderAssign = new int[n];
        double leaderFit = Double.MAX_VALUE;

        int[] assign = new int[n];
        double[] peFree = new double[model.totalPes];

        for (int t = 0; t < ITERATIONS; t++) {

            /* Memperbarui posisi setiap paus (kecuali evaluasi awal) */
            if (t > 0) {
                double a = 2.0 - 2.0 * t / ITERATIONS;      // 2 -> 0
                double a2 = -1.0 - (double) t / ITERATIONS; // -1 -> -2

                for (int w = 0; w < WHALES; w++) {
                    double r1 = rnd.nextDouble();
                    double r2 = rnd.nextDouble();
                    double bigA = 2.0 * a * r1 - a;
                    double bigC = 2.0 * r2;
                    double l = (a2 - 1.0) * rnd.nextDouble() + 1.0;
                    double p = rnd.nextDouble();

                    for (int d = 0; d < n; d++) {
                        double x = pos[w][d];
                        double next;

                        if (p < 0.5) {
                            if (Math.abs(bigA) >= 1.0) {
                                /* Fase pencarian: ikuti paus acak */
                                double xr = pos[rnd.nextInt(WHALES)][d];
                                next = xr - bigA * Math.abs(bigC * xr - x);
                            } else {
                                /* Fase pengepungan: dekati posisi terbaik */
                                next = leader[d]
                                        - bigA * Math.abs(bigC * leader[d] - x);
                            }
                        } else {
                            /* Fase spiral mengitari posisi terbaik */
                            double dist = Math.abs(leader[d] - x);
                            next = dist * Math.exp(B * l)
                                    * Math.cos(2.0 * Math.PI * l) + leader[d];
                        }

                        pos[w][d] = Math.min(upper, Math.max(0.0, next));
                    }
                }
            }

            /* Mengevaluasi seluruh paus dan memperbarui posisi terbaik */
            for (int w = 0; w < WHALES; w++) {
                for (int d = 0; d < n; d++) {
                    assign[d] = (int) pos[w][d];
                }

                double fit = model.fitness(assign, length, peFree);

                if (fit < leaderFit) {
                    leaderFit = fit;
                    System.arraycopy(pos[w], 0, leader, 0, n);
                    System.arraycopy(assign, 0, leaderAssign, 0, n);
                }
            }
        }

        return new Solution(leaderAssign, leaderFit,
                model.makespan(leaderAssign, length, peFree));
    }


    /* ====================================================================== */
    /* Infrastruktur CloudSim (sama dengan referensi MET)                     */
    /* ====================================================================== */

    private static Datacenter createDatacenter(String name) {
        /* Membuat host dengan dua kelas spesifikasi */
        List<Host> hostList = new ArrayList<>();

        for (int hostId = 0; hostId < NUM_HOSTS; hostId++) {
            boolean classA = hostId < 5;

            int hostPes = classA ? 4 : 8;
            int hostMips = classA ? 2000 : 3000;
            int ram = classA ? 8192 : 16384;
            long storage = classA ? 1_000_000L : 2_000_000L;
            int bw = 1_000_000;

            /* Menambahkan unit pemrosesan pada host */
            List<Pe> peList = new ArrayList<>();

            for (int peId = 0; peId < hostPes; peId++) {
                peList.add(
                    new Pe(
                        peId,
                        new PeProvisionerSimple(hostMips)
                    )
                );
            }

            hostList.add(
                new Host(
                    hostId,
                    new RamProvisionerSimple(ram),
                    new BwProvisionerSimple(bw),
                    storage,
                    peList,
                    new VmSchedulerTimeShared(peList)
                )
            );
        }

        String arch = "x86";
        String os = "Linux";
        String vmm = "Xen";
        double timeZone = 7.0;
        double cost = 3.0;
        double costPerMem = 0.05;
        double costPerStorage = 0.001;
        double costPerBw = 0.0;
        List<Storage> storageList = new ArrayList<>();

        /* Menetapkan karakteristik dan kebijakan alokasi datacenter */
        DatacenterCharacteristics characteristics =
                new DatacenterCharacteristics(
                    arch,
                    os,
                    vmm,
                    hostList,
                    timeZone,
                    cost,
                    costPerMem,
                    costPerStorage,
                    costPerBw
                );

        try {
            return new Datacenter(
                name,
                characteristics,
                new VmAllocationPolicySimple(hostList),
                storageList,
                0
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to create datacenter.", e);
        }
    }

    private static List<Vm> createVmList(int brokerId) {
        List<Vm> list = new ArrayList<>();

        /*
         * Five VMs of each type.
         *
         * Type 1: 1 PE, 1000 MIPS, 2 GB RAM, 20 GB storage
         * Type 2: 2 PE, 1500 MIPS, 2 GB RAM, 20 GB storage
         * Type 3: 2 PE, 2000 MIPS, 4 GB RAM, 30 GB storage
         * Type 4: 4 PE, 2500 MIPS, 4 GB RAM, 40 GB storage
         */
        int[] pes =    {1, 2, 2, 4};
        int[] mips =   {1000, 1500, 2000, 2500};
        int[] ram =    {2048, 2048, 4096, 4096};
        long[] size =  {20_000L, 20_000L, 30_000L, 40_000L};

        long bw = 10_000L;
        String vmm = "Xen";

        int vmId = 0;

        /* Membuat lima VM untuk setiap tipe */
        for (int type = 0; type < 4; type++) {
            for (int copy = 0; copy < 5; copy++) {
                Vm vm = new Vm(
                    vmId,
                    brokerId,
                    mips[type],
                    pes[type],
                    ram[type],
                    bw,
                    size[type],
                    vmm,
                    new CloudletSchedulerSpaceShared()
                );

                list.add(vm);
                vmId++;
            }
        }

        return list;
    }

    private static List<Cloudlet> createCloudletList(
            int brokerId,
            List<Long> jobLengths) {

        List<Cloudlet> list = new ArrayList<>();

        int pesNumber = 1;
        long fileSize = 300;
        long outputSize = 300;

        UtilizationModel utilizationModel = new UtilizationModelFull();

        /* Membuat Cloudlet dari daftar panjang pekerjaan */
        for (int i = 0; i < jobLengths.size(); i++) {
            Cloudlet cloudlet = new Cloudlet(
                i,
                jobLengths.get(i),
                pesNumber,
                fileSize,
                outputSize,
                utilizationModel,
                utilizationModel,
                utilizationModel
            );

            cloudlet.setUserId(brokerId);
            list.add(cloudlet);
        }

        return list;
    }

    private static List<Long> loadGoCJ(Path path) throws IOException {
        if (!Files.exists(path)) {
            throw new IOException("Dataset file not found: " + path);
        }

        /* Membaca dan memvalidasi nilai MI dari dataset */
        List<Long> lengths = new ArrayList<>();

        try (BufferedReader reader = Files.newBufferedReader(path)) {
            String line;

            while ((line = reader.readLine()) != null) {
                line = line.trim();

                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }

                String[] tokens = line.split("[,;\\t ]+");

                try {
                    long value = Long.parseLong(
                        tokens[0].replace("\"", "").trim()
                    );

                    if (value > 0) {
                        lengths.add(value);
                    }
                } catch (NumberFormatException ignored) {
                    // Skip header or non-numeric rows.
                }
            }
        }

        if (lengths.isEmpty()) {
            throw new IOException("No numeric MI values were found in " + path);
        }

        return lengths;
    }

    /* ====================================================================== */
    /* Output dan metrik                                                      */
    /* ====================================================================== */

    private static void printConfiguration(
            List<Long> jobLengths,
            List<Vm> vmList,
            Map<Integer, Integer> assignment,
            int run,
            long seed,
            Solution solution,
            double schedulingMs) {

        /* Menghitung pembagian Cloudlet pada setiap VM */
        Map<Integer, Integer> countPerVm = new LinkedHashMap<>();

        for (Vm vm : vmList) {
            countPerVm.put(vm.getId(), 0);
        }

        for (int vmId : assignment.values()) {
            countPerVm.put(vmId, countPerVm.get(vmId) + 1);
        }

        Log.println();
        Log.println("========== CONFIGURATION ==========");
        Log.println("Datacenters : 1");
        Log.println("Hosts       : 10 (5 Class A + 5 Class B)");
        Log.println("VMs         : " + vmList.size());
        Log.println("Cloudlets   : " + jobLengths.size());
        Log.println("Algorithm   : WOA (Whale Optimization Algorithm)");
        Log.println("Parameters  : " + parameterSummary());
        Log.println("Run / Seed  : " + run + " / " + seed);
        Log.println("Estimated makespan             : "
                + DF.format(solution.makespan) + " s");
        Log.println("Fitness (makespan + tie-break) : "
                + DF.format(solution.fitness));
        Log.println("Scheduling time              : "
                + DF.format(schedulingMs) + " ms");
        Log.println();
        Log.println("VM assignment distribution:");

        for (Map.Entry<Integer, Integer> entry : countPerVm.entrySet()) {
            Log.println(
                "VM " + entry.getKey()
                + " -> " + entry.getValue() + " cloudlets"
            );
        }

        Log.println();
        Log.println("First 20 WOA assignments:");

        int printed = 0;
        for (Map.Entry<Integer, Integer> entry : assignment.entrySet()) {
            Log.println(
                "Cloudlet " + entry.getKey()
                + " -> VM " + entry.getValue()
            );

            printed++;
            if (printed >= 20) {
                break;
            }
        }
    }

    private static void printCloudletResults(List<Cloudlet> list) {
        Log.println();
        Log.println("========== CLOUDLET RESULTS ==========");
        Log.println(
            "(only the first 20 successful Cloudlets are shown; "
            + "METRICS below are still computed from all "
            + list.size() + " results)"
        );

        /* Menampilkan maksimal 20 Cloudlet yang berhasil */
        Log.println(
            "Cloudlet ID\tStatus\tVM ID\tExec Time\tStart Time\tFinish Time"
        );

        int shown = 0;
        for (Cloudlet cloudlet : list) {
            if (shown >= 20) {
                break;
            }

            if (cloudlet.getStatus() == Cloudlet.CloudletStatus.SUCCESS) {
                Log.println(
                    cloudlet.getCloudletId()
                    + "\tSUCCESS\t"
                    + cloudlet.getGuestId()
                    + "\t"
                    + DF.format(cloudlet.getActualCPUTime())
                    + "\t"
                    + DF.format(cloudlet.getExecStartTime())
                    + "\t"
                    + DF.format(cloudlet.getExecFinishTime())
                );
                shown++;
            }
        }
    }

    /**
     * Menghitung dan mencetak metrik (sama dengan referensi MET, kecuali utilisasi yang berbasis PE).
     *
     * @return {makespan, degreeOfImbalance, averageWaitingTime,
     *          throughput, utilization, utilizationLegacy}
     */
    private static double[] printMetrics(
            List<Cloudlet> results,
            List<Vm> vmList) {

        /* Menyiapkan nilai dasar untuk perhitungan metrik */
        if (results.isEmpty()) {
            throw new IllegalStateException("No Cloudlet results were returned.");
        }

        double makespan = 0.0;
        double totalWaitingTime = 0.0;

        Map<Integer, Double> loadPerVm = new HashMap<>();

        for (Vm vm : vmList) {
            loadPerVm.put(vm.getId(), 0.0);
        }

        /* Mengumpulkan waktu dan beban dari Cloudlet yang berhasil */
        for (Cloudlet cloudlet : results) {
            if (cloudlet.getStatus() != Cloudlet.CloudletStatus.SUCCESS) {
                continue;
            }

            double start = cloudlet.getExecStartTime();
            double finish = cloudlet.getExecFinishTime();
            double actualCpu = cloudlet.getActualCPUTime();

            makespan = Math.max(makespan, finish);
            totalWaitingTime += Math.max(0.0, start);

            int vmId = cloudlet.getGuestId();
            loadPerVm.put(
                vmId,
                loadPerVm.get(vmId) + actualCpu
            );
        }

        /* Menghitung ketimpangan beban dan metrik simulasi */
        double averageLoad =
                loadPerVm.values()
                    .stream()
                    .mapToDouble(Double::doubleValue)
                    .average()
                    .orElse(0.0);

        double maxLoad =
                loadPerVm.values()
                    .stream()
                    .mapToDouble(Double::doubleValue)
                    .max()
                    .orElse(0.0);

        double minLoad =
                loadPerVm.values()
                    .stream()
                    .mapToDouble(Double::doubleValue)
                    .min()
                    .orElse(0.0);

        double degreeOfImbalance =
                averageLoad == 0.0
                ? 0.0
                : (maxLoad - minLoad) / averageLoad;

        double totalActualCpuTime =
                results.stream()
                    .filter(c -> c.getStatus() == Cloudlet.CloudletStatus.SUCCESS)
                    .mapToDouble(Cloudlet::getActualCPUTime)
                    .sum();

        double averageWaitingTime =
                totalWaitingTime / results.size();

        double throughput =
                makespan == 0.0
                ? 0.0
                : results.size() / makespan;

        /*
         * Utilisasi berbasis PE: total waktu CPU Cloudlet dibagi total
         * PE-time yang tersedia (makespan x jumlah seluruh PE VM). Nilainya
         * selalu <= 100%. Rumus referensi MET lama membagi dengan
         * (makespan x jumlah VM) sehingga bisa melebihi 100% untuk VM
         * multi-core; nilai itu tetap dicatat sebagai utilizationLegacy.
         */
        int totalPes = 0;
        for (Vm vm : vmList) {
            totalPes += vm.getNumberOfPes();
        }

        double utilization =
                makespan == 0.0
                ? 0.0
                : (totalActualCpuTime / (makespan * totalPes)) * 100.0;

        double utilizationLegacy =
                makespan == 0.0
                ? 0.0
                : (totalActualCpuTime
                    / (makespan * vmList.size())) * 100.0;

        Log.println();
        Log.println("========== METRICS ==========");
        Log.println("Completed Cloudlets      : " + results.size());
        Log.println("Makespan                 : " + DF.format(makespan) + " s");
        Log.println("Degree of Imbalance      : " + DF.format(degreeOfImbalance));
        Log.println("Average Waiting Time     : " + DF.format(averageWaitingTime) + " s");
        Log.println("Throughput               : " + DF.format(throughput) + " cloudlets/s");
        Log.println("Average VM Utilization*  : " + DF.format(utilization) + " %");

        Log.println("Utilization (MET formula): " + DF.format(utilizationLegacy)
                + " % (can exceed 100% on multi-core VMs)");

        Log.println();
        Log.println("* Utilization = total Cloudlet CPU time / (makespan x "
                + totalPes + " total VM PEs).");

        return new double[] {
            makespan, degreeOfImbalance, averageWaitingTime,
            throughput, utilization, utilizationLegacy
        };
    }

    /** Menambahkan satu baris hasil ke file CSV (header dibuat otomatis). */
    private static void appendCsv(
            Path path,
            int numTasks,
            int run,
            long seed,
            int completed,
            double[] m,
            double estimatedMakespan,
            double schedulingMs) throws IOException {

        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }

        StringBuilder sb = new StringBuilder();

        if (!Files.exists(path) || Files.size(path) == 0) {
            sb.append("Family,Dataset,Tasks,Run,Seed,Completed Cloudlets,")
              .append("Makespan (s),Degree of Imbalance,")
              .append("Average Waiting Time (s),Throughput (cloudlets/s),")
              .append("Average VM Utilization (%),")
              .append("Estimated Makespan (s),Scheduling Time (ms),")
              .append("Utilization MET Formula (%)\n");
        }

        sb.append(FAMILY).append(',')
          .append(FAMILY).append('_').append(numTasks).append(',')
          .append(numTasks).append(',')
          .append(run).append(',')
          .append(seed).append(',')
          .append(completed).append(',')
          .append(DF.format(m[0])).append(',')
          .append(DF.format(m[1])).append(',')
          .append(DF.format(m[2])).append(',')
          .append(DF.format(m[3])).append(',')
          .append(DF.format(m[4])).append(',')
          .append(DF.format(estimatedMakespan)).append(',')
          .append(DF.format(schedulingMs)).append(',')
          .append(DF.format(m[5])).append('\n');

        Files.write(
            path,
            sb.toString().getBytes(StandardCharsets.UTF_8),
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND
        );

        Log.println("Result appended to " + path);
    }
}
