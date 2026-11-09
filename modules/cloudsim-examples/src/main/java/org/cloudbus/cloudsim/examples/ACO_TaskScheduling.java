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
 * Runner CloudSim sekaligus implementasi Ant Colony Optimization (ACO) untuk
 * memetakan setiap Cloudlet ke satu VM. Cloudlet adalah pekerjaan dengan panjang
 * dalam MI, sedangkan VM memiliki kecepatan MIPS dan sejumlah PE.
 *
 * Alur kerja:
 * 1. Membuat CloudSim, datacenter, broker, 20 VM, dan Cloudlet dari dataset.
 * 2. Menjalankan ACO untuk membangun dan memperbaiki pemetaan Cloudlet ke VM.
 * 3. Mengikat pemetaan ke Cloudlet melalui setGuestId, menjalankan simulasi,
 *    lalu menghitung metrik dari hasil CloudSim dan menulis CSV.
 *
 * Input kelas ini adalah argumen jumlah task, nomor run, seed, path CSV, dan
 * path dataset. Outputnya adalah log konfigurasi dan hasil, enam metrik simulasi,
 * serta satu baris CSV. Analogi ACO-nya adalah semut yang mencoba rute: pilihan
 * yang baik meninggalkan pheromone (jejak feromon) agar semut berikutnya lebih
 * sering mencoba pilihan tersebut.
 *
 * Cara membaca hubungan antar-method:
 * - main memanggil createVmList untuk membuat VM, lalu loadGoCJ dan
 *   createCloudletList untuk mengubah data MI menjadi Cloudlet.
 * - main membuat VmModel dan array panjang job, lalu memberikannya ke runACO
 *   bersama Random. runACO mengulang ITERATIONS kali; pada setiap iterasi,
 *   ANTS semut membangun assignment dan memberikannya ke VmModel.fitness.
 * - Setelah runACO mengembalikan Solution terbaik, main membaca assignment,
 *   mengubah indeks VM menjadi VM ID, lalu memanggil setGuestId.
 * - Setelah CloudSim selesai, main memberikan hasil ke printMetrics, lalu
 *   array metrik diberikan ke appendCsv.
 *
 * Jalankan dari root repository:
 * ACO_TaskScheduling [numTasks] [run] [seed] [outputCsv] [datasetPath]
 */
public class ACO_TaskScheduling {

    /** Jumlah pengguna CloudSim; satu broker mewakili satu eksperimen. */
    private static final int NUM_USERS = 1;
    /** Jumlah host; dibesarkan menambah kapasitas, tetapi juga sumber daya simulasi. */
    private static final int NUM_HOSTS = 10;
    /** Jumlah VM kandidat; dibesarkan memperluas pilihan penjadwalan. */
    private static final int NUM_VMS = 20;

    /* Dataset GoCJ per ukuran: dataset/GoCJ_Dataset_<jumlahTask>.txt */
    /** Membentuk path dataset default dari jumlah Cloudlet yang diminta. */
    private static String defaultDatasetPath(int numTasks) {
        return "dataset/GoCJ_Dataset_" + numTasks + ".txt";
    }
    /** File CSV default untuk hasil run ACO. */
    private static final String DEFAULT_CSV = "results/aco_manual.csv";
    /** Label keluarga dataset yang ditulis ke CSV. */
    private static final String FAMILY = "GoCJ";

    /* Locale.ROOT agar titik desimal tetap '.' di komputer berlokal Indonesia */
    /** Format angka log dan CSV agar pemisah desimal selalu titik. */
    private static final DecimalFormat DF =
            new DecimalFormat("0.####", DecimalFormatSymbols.getInstance(Locale.ROOT));

    /* ---------- Parameter ACO ---------- */
    /** Jumlah semut per iterasi; lebih besar memperluas eksplorasi tetapi lebih lambat. */
    private static final int ANTS = 30;
    /** Jumlah iterasi; lebih besar memberi kesempatan belajar lebih lama. */
    private static final int ITERATIONS = 100;       // 30 x 100 = 3000 evaluasi
    /** Bobot pheromone; lebih besar lebih mengikuti pengalaman semut sebelumnya. */
    private static final double ALPHA = 1.0;         // bobot feromon
    /** Bobot heuristik; lebih besar lebih memilih VM yang cepat saat ini. */
    private static final double BETA = 2.0;          // bobot heuristik
    /** Laju penguapan; lebih besar melupakan jejak lama lebih cepat. */
    private static final double RHO = 0.3;           // laju penguapan
    /** Skala deposit pheromone; lebih besar memperkuat solusi terpilih. */
    private static final double Q = 1.0;             // konstanta deposit feromon
    /** Nilai awal pheromone; lebih besar membuat awal pencarian lebih netral. */
    private static final double TAU_INIT = 1.0;
    /** Batas bawah pheromone agar pilihan tidak hilang sepenuhnya. */
    private static final double TAU_MIN = 0.01;
    /** Batas atas pheromone agar satu pilihan tidak mendominasi tanpa batas. */
    private static final double TAU_MAX = 2.0 * Q / RHO;


    /**
     * Parameter: args berisi jumlah task, run, seed, CSV, dan dataset opsional.
     * Nilai kembali: tidak ada. Efeknya menjalankan simulasi dan menulis output.
     * Urutan tahap sengaja mengikuti alur presentasi: konfigurasi, VM, Cloudlet,
     * penjadwalan, simulasi, lalu metrik.
     */
    public static void main(String[] args) {
        int numTasks = args.length > 0 ? Integer.parseInt(args[0]) : 1000;
        int run = args.length > 1 ? Integer.parseInt(args[1]) : 1;
        long seed = args.length > 2 ? Long.parseLong(args[2]) : run;
        String csvPath = args.length > 3 ? args[3] : DEFAULT_CSV;
        String datasetPath = args.length > 4 ? args[4] : defaultDatasetPath(numTasks);

        Log.println("Starting ACO_TaskScheduling...");
        try {
            /* BAGIAN 1: Konfigurasi datacenter dan host, lalu broker. */
            CloudSim.init(NUM_USERS, Calendar.getInstance(), false);

            Datacenter datacenter = createDatacenter("Datacenter_0");
            DatacenterBroker broker = new DatacenterBroker("ACO_Broker");
            int brokerId = broker.getId();

            /* BAGIAN 2 dan 3: Menyiapkan VM dan membaca N panjang pekerjaan
               pertama dari dataset untuk dibuat menjadi Cloudlet. */
            // createVmList menerima brokerId dan mengembalikan daftar VM.
            List<Vm> vmList = createVmList(brokerId);

            // loadGoCJ membaca nilai MI dari file, lalu createCloudletList
            // mengubah setiap nilai MI menjadi satu Cloudlet milik broker.
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

            /* BAGIAN 4: Penjadwalan. ACO memilih indeks VM untuk setiap Cloudlet. */
            // runACO menerima panjang job, model VM, dan sumber acak. Di dalamnya
            // ada loop ITERATIONS x ANTS; setiap assignment dikirim ke fitness().
            VmModel model = new VmModel(vmList);
            double[] length = new double[jobLengths.size()];
            for (int i = 0; i < length.length; i++) {
                length[i] = jobLengths.get(i);
            }

            Random rnd = new Random(seed);
            long startNs = System.nanoTime();
            Solution solution = runACO(length, model, rnd);
            double schedulingMs = (System.nanoTime() - startNs) / 1_000_000.0;

            Map<Integer, Integer> assignment = new LinkedHashMap<>();
            // Solution.assign berisi indeks VM, bukan VM ID. Indeks itu dibaca
            // dari vmList untuk mendapatkan ID yang dipakai CloudSim.
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

            /* BAGIAN 5: Menjalankan simulasi CloudSim dan mengambil hasilnya. */
            CloudSim.startSimulation();
            CloudSim.stopSimulation();

            List<Cloudlet> results = broker.getCloudletReceivedList();

            printCloudletResults(results);
            /* BAGIAN 6: Menghitung metrik dari seluruh hasil CloudSim. */
            double[] metrics = printMetrics(results, vmList);

            appendCsv(Paths.get(csvPath), numTasks, run, seed,
                    results.size(), metrics, solution.makespan, schedulingMs);

            Log.println("ACO_TaskScheduling finished!");

        } catch (Exception e) {
            e.printStackTrace();
            Log.println("Simulation failed.");
            System.exit(1);
        }
    }

    /* ====================================================================== */
    /* Algoritma                                                              */
    /* ====================================================================== */

    /** Menyimpan pemetaan indeks VM, fitness terendah, dan makespan estimasi. */
    private static final class Solution {
        final int[] assign;
        final double fitness;   // makespan + tie-break (yang diminimalkan)
        final double makespan;  // estimasi makespan murni

        /** Parameter: pemetaan, fitness, dan makespan; return: objek solusi baru. */
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

        /**
         * Parameter: vmList sebagai sumber MIPS dan PE; return: model VM baru.
         * Alasan: susunan PE diringkas sekali agar fitness tidak membuat objek
         * CloudSim berulang kali.
         */
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
        /** Bobot rata-rata selesai; lebih besar lebih kuat memecah tie makespan. */
        static final double TIE_BREAK = 0.001;

        private double lastMakespan;

        /**
         * Parameter: assignment, panjang MI, dan buffer waktu bebas PE.
         * Return: jumlah waktu selesai semua Cloudlet; makespan disimpan internal.
         * Alasan: ini meniru antrean FIFO CloudletSchedulerSpaceShared secara cepat.
         */
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

        /**
         * Parameter: assignment, panjang MI, dan buffer PE; return: fitness yang
         * diminimalkan. Makespan menjadi tujuan utama, sedangkan rata-rata selesai
         * berbobot kecil memecah solusi yang memiliki makespan sama.
         */
        double fitness(int[] assign, double[] length, double[] peFree) {
            double sumFinish = simulate(assign, length, peFree);
            return lastMakespan + TIE_BREAK * sumFinish / assign.length;
        }

        /** Parameter: assignment, panjang MI, buffer PE; return: makespan estimasi. */
        double makespan(int[] assign, double[] length, double[] peFree) {
            simulate(assign, length, peFree);
            return lastMakespan;
        }
    }

    /* ====================================================================== */
    /* ACO (Ant Colony Optimization)                                          */
    /* ====================================================================== */

    /** Return: ringkasan parameter ACO untuk log, tanpa mengubah parameter. */
    private static String parameterSummary() {
        return "ants=" + ANTS + ", iterations=" + ITERATIONS
                + ", alpha=" + ALPHA + ", beta=" + BETA
                + ", rho=" + RHO + ", Q=" + Q;
    }

    /** Parameter: basis dan pangkat; return: hasil pangkat dengan jalur cepat. */
    private static double pow(double x, double e) {
        if (e == 1.0) {
            return x;
        }
        if (e == 2.0) {
            return x * x;
        }
        return Math.pow(x, e);
    }

    /**
     * Setiap semut membangun jadwal Cloudlet demi Cloudlet (urutan id).
     * Peluang memilih VM j untuk Cloudlet i:
     *   p(i,j) ~ tau[i][j]^ALPHA * eta(i,j)^BETA
     * dengan eta(i,j) = 1 / (waktu selesai Cloudlet i jika ditaruh di VM j),
     * sehingga semut mempertimbangkan kecepatan VM sekaligus antrean yang
     * sudah terbentuk. Feromon diperbarui dengan penguapan, lalu deposit dari
     * semut terbaik iterasi dan semut terbaik global (elitist), dengan batas
     * [TAU_MIN, TAU_MAX] agar tidak macet pada satu jalur.
     */
    private static Solution runACO(double[] length, VmModel model, Random rnd) {
        int n = length.length;
        int m = model.count;

        double[][] tau = new double[n][m];
        for (double[] row : tau) {
            Arrays.fill(row, TAU_INIT);
        }

        double[] peFree = new double[model.totalPes];
        double[] earliest = new double[m];
        double[] weight = new double[m];

        int[] bestAssign = null;
        double bestFit = Double.MAX_VALUE;

        for (int iter = 0; iter < ITERATIONS; iter++) {
            int[] iterAssign = null;
            double iterFit = Double.MAX_VALUE;

            for (int ant = 0; ant < ANTS; ant++) {
                int[] assign = new int[n];
                Arrays.fill(peFree, 0.0);
                Arrays.fill(earliest, 0.0);

                /* Membangun solusi satu semut */
                for (int i = 0; i < n; i++) {
                    double sum = 0.0;

                    for (int j = 0; j < m; j++) {
                        // eta memasukkan waktu antrean, sehingga VM cepat yang sudah
                        // penuh tidak selalu mengalahkan VM lain yang lebih kosong.
                        double completion =
                                earliest[j] + length[i] / model.mips[j];
                        double eta = 1.0 / completion;
                        weight[j] = pow(tau[i][j], ALPHA) * pow(eta, BETA);
                        sum += weight[j];
                    }

                    // Roulette memberi peluang proporsional, bukan selalu memilih
                    // bobot terbesar, sehingga eksplorasi tetap berlangsung.
                    int chosen = roulette(weight, sum, rnd);
                    assign[i] = chosen;

                    /* Perbarui waktu bebas PE VM terpilih */
                    int from = model.offset[chosen];
                    int to = from + model.pes[chosen];
                    int k = from;
                    for (int q = from + 1; q < to; q++) {
                        if (peFree[q] < peFree[k]) {
                            k = q;
                        }
                    }
                    peFree[k] += length[i] / model.mips[chosen];

                    double min = peFree[from];
                    for (int q = from + 1; q < to; q++) {
                        min = Math.min(min, peFree[q]);
                    }
                    earliest[chosen] = min;
                }

                double fit = model.fitness(assign, length, peFree);

                if (fit < iterFit) {
                    iterFit = fit;
                    iterAssign = assign;
                }
            }

            if (iterFit < bestFit) {
                bestFit = iterFit;
                bestAssign = iterAssign.clone();
            }

            /* tau_ij = (1 - rho) x tau_ij: jejak lama dikurangi agar solusi baru
               tetap dapat masuk, lalu deposit memperkuat pilihan yang baik. */
            /* Penguapan feromon */
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < m; j++) {
                    tau[i][j] = Math.max(TAU_MIN, tau[i][j] * (1.0 - RHO));
                }
            }

            /* Deposit semut terbaik iterasi diberi rasio terhadap terbaik global:
               semakin dekat kualitasnya, semakin besar tambahan jejaknya. */
            double iterDeposit = Q * (bestFit / iterFit);
            for (int i = 0; i < n; i++) {
                int j = iterAssign[i];
                tau[i][j] = Math.min(TAU_MAX, tau[i][j] + iterDeposit);
            }

            /* Deposit elitist: semut terbaik global */
            for (int i = 0; i < n; i++) {
                int j = bestAssign[i];
                tau[i][j] = Math.min(TAU_MAX, tau[i][j] + Q);
            }
        }

        return new Solution(bestAssign, bestFit,
                model.makespan(bestAssign, length, peFree));
    }

    /**
     * Parameter: bobot pilihan, jumlah bobot, dan sumber acak; return: indeks VM.
     * Alasan: pemilihan proporsional menjaga keseimbangan eksplorasi dan eksploitasi.
     */
    private static int roulette(double[] weight, double sum, Random rnd) {
        if (!(sum > 0.0) || Double.isNaN(sum) || Double.isInfinite(sum)) {
            return rnd.nextInt(weight.length);
        }

        double r = rnd.nextDouble() * sum;
        double acc = 0.0;

        for (int j = 0; j < weight.length; j++) {
            acc += weight[j];
            if (r <= acc) {
                return j;
            }
        }

        return weight.length - 1;
    }


    /* ====================================================================== */
    /* Infrastruktur CloudSim (sama dengan referensi MET)                     */
    /* ====================================================================== */

    /**
     * Parameter: nama datacenter; return: datacenter berisi 10 host heterogen.
     * Alasan: infrastruktur tetap sama dengan pembanding agar perbedaan hasil
     * terutama berasal dari algoritma penjadwalan.
     */
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

    /**
     * Parameter: id broker pemilik VM; return: 20 VM dalam empat tipe.
     * Alasan: heterogenitas MIPS dan PE membuat pemetaan task menjadi masalah optimasi.
     */
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

    /**
     * Parameter: brokerId dan panjang job dalam MI; return: Cloudlet satu-PE.
     * Alasan: setiap nilai dataset menjadi satu task yang dapat diikat ke VM.
     */
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

    /**
     * Parameter: path dataset; return: daftar MI positif.
     * Alasan: header, komentar, dan baris nonangka dilewati agar input GoCJ
     * dapat dibaca tanpa mengubah data valid; IOException menandai input gagal.
     */
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

    /**
     * Parameter: panjang job, VM, assignment, run, seed, solusi, waktu algoritma.
     * Return: tidak ada. Menampilkan konfigurasi dan sebagian pemetaan untuk audit.
     */
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
        Log.println("Algorithm   : ACO (Ant Colony Optimization)");
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
        Log.println("First 20 ACO assignments:");

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

    /** Parameter: hasil CloudSim; return: tidak ada. Menampilkan maksimal 20 hasil sukses. */
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
     * Parameter: hasil CloudSim dan daftar VM; return: enam metrik dalam urutan CSV.
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

        // DoI = (beban maksimum - beban minimum) / beban rata-rata.
        /*
         * Rumus DoI = (beban maksimum - beban minimum) / beban rata-rata.
         * Nilai lebih kecil berarti pembagian beban antar VM lebih seimbang.
         */
        double degreeOfImbalance =
                averageLoad == 0.0
                ? 0.0
                : (maxLoad - minLoad) / averageLoad;

        double totalActualCpuTime =
                results.stream()
                    .filter(c -> c.getStatus() == Cloudlet.CloudletStatus.SUCCESS)
                    .mapToDouble(Cloudlet::getActualCPUTime)
                    .sum();

        // Waiting time di implementasi ini memakai waktu mulai Cloudlet sejak t=0.
        /*
         * Rumus average waiting time = jumlah waktu mulai Cloudlet / jumlah
         * hasil. Kode memakai start sejak waktu simulasi 0 sebagai proksi antrean.
         */
        double averageWaitingTime =
                totalWaitingTime / results.size();

        // Throughput = jumlah Cloudlet selesai / makespan.
        /*
         * Rumus throughput = jumlah Cloudlet selesai / makespan, dalam
         * Cloudlet per detik.
         */
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

        // Utilisasi = total waktu CPU / (makespan x total PE) x 100%.
        /*
         * Rumus utilization = total waktu CPU Cloudlet /
         * (makespan x total PE VM) x 100 persen.
         */
        double utilization =
                makespan == 0.0
                ? 0.0
                : (totalActualCpuTime / (makespan * totalPes)) * 100.0;

        // Rumus lama MET memakai jumlah VM, bukan total PE, dan dipertahankan
        // hanya untuk pembanding pada CSV.
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
    /**
     * Parameter: path, ukuran run, seed, jumlah hasil, metrik, makespan estimasi,
     * dan waktu penjadwalan; return: tidak ada. Menambah header bila perlu lalu
     * menulis satu baris hasil agar eksperimen dapat digabungkan.
     */
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
