package probe;

import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.spark.sql.SparkSession;

/**
 * M3 最小探针（2026-09-12）：在 **YARN 容器内**证明四件事，全部只读或只写 /graduation 下自有路径。
 *
 * <ol>
 *   <li>容器内能写 HDFS（写 /graduation/probe/from-container/&lt;host&gt;.txt 并回读长度）；</li>
 *   <li>容器内能用 Spark 读回刚写的文件（证明不是"写了个寂寞"）；</li>
 *   <li>容器内能连上 Hive Metastore（thrift://node01:9083）并列库 —— <b>只读</b>，不建库不建表；</li>
 *   <li>把上述结论与 <b>容器 JVM 版本</b>写进 /graduation/probe/result/probe-result.txt。</li>
 * </ol>
 *
 * <p><b>为什么要把结论写进 HDFS</b>：实测本集群 YARN 日志聚合是 TFile(bucket) 二进制格式
 * （/tmp/logs/root/bucket-logs-tfile），容器 stdout 无法用普通工具读取，因此"stdout 打印"不足以成为证据；
 * 落 HDFS 的文本文件才是可复核证据。E4 的 1,000 行链路同样按此约定产出。
 *
 * <p><b>为什么 --release 8</b>：实测集群容器 JVM 为 Java 8（AM 曾以
 * UnsupportedClassVersionError class file version 61.0 失败，见 README §Probe）。
 * 本文件不是产品代码，不进任何生产 jar。
 */
public final class ClusterProbe {

    private ClusterProbe() {
    }

    public static void main(String[] args) throws Exception {
        SparkSession spark = SparkSession.builder()
                .appName("m3-cluster-probe")
                .enableHiveSupport()
                .getOrCreate();

        List<String> lines = new ArrayList<String>();
        String host = InetAddress.getLocalHost().getHostName();
        Configuration hconf = spark.sparkContext().hadoopConfiguration();
        FileSystem fs = FileSystem.get(URI.create("hdfs://node01:8020"), hconf);

        lines.add("PROBE spark.version=" + spark.version());
        lines.add("PROBE scala.version=" + scala.util.Properties.versionNumberString());
        lines.add("PROBE master=" + spark.sparkContext().master());
        lines.add("PROBE appId=" + spark.sparkContext().applicationId());
        lines.add("PROBE sparkUser=" + spark.sparkContext().sparkUser());
        lines.add("PROBE containerHost=" + host);
        lines.add("PROBE java.version=" + System.getProperty("java.version"));
        lines.add("PROBE java.class.version=" + System.getProperty("java.class.version"));
        lines.add("PROBE java.home=" + System.getProperty("java.home"));
        lines.add("PROBE defaultFS=" + hconf.get("fs.defaultFS"));
        lines.add("PROBE hive.metastore.uris=" + hconf.get("hive.metastore.uris"));

        // (1) 容器内写 HDFS
        Path dir = new Path("/graduation/probe/from-container");
        fs.mkdirs(dir);
        Path file = new Path(dir, "container-" + host + ".txt");
        try (FSDataOutputStream os = fs.create(file, true)) {
            os.writeBytes("written-by-container host=" + host + " appId=" + spark.sparkContext().applicationId() + "\n");
        }
        long len = fs.getFileStatus(file).getLen();
        lines.add("PROBE hdfs.write=" + file + " len=" + len + " replicas=" + fs.getFileStatus(file).getReplication());
        lines.add("PROBE hdfs.blockLocations=" + fs.getFileStatus(file).getPath());

        // (2) 用 Spark 读回（证明写入可被计算层消费）
        long readback = spark.read().textFile("hdfs://node01:8020/graduation/probe/from-container/*.txt").count();
        lines.add("PROBE hdfs.readback.lines=" + readback);
        lines.add("PROBE spark.parallelism.readback=" + spark.sparkContext().defaultParallelism());

        // (3) Metastore 只读列库
        String dbs;
        try {
            dbs = spark.sql("SHOW DATABASES").collectAsList().toString();
            lines.add("PROBE metastore.databases=" + dbs);
            lines.add("PROBE metastore.reachable=TRUE");
        } catch (Exception e) {
            lines.add("PROBE metastore.reachable=FALSE error=" + e.getClass().getName() + ": " + e.getMessage());
        }

        // (4) 结论落 HDFS（唯一可靠观测通道）
        lines.add("PROBE done=TRUE");
        Path resultDir = new Path("/graduation/probe/result");
        fs.mkdirs(resultDir);
        Path result = new Path(resultDir, "probe-result.txt");
        try (FSDataOutputStream os = fs.create(result, true);
             PrintWriter pw = new PrintWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8))) {
            for (String l : lines) {
                pw.println(l);
            }
        }
        for (String l : lines) {
            System.out.println(l);
        }

        spark.stop();
    }
}
