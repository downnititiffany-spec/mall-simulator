object ReleaseProbe {
  def main(args: Array[String]): Unit = {
    // Java 11 API: 在 JDK 8 的 ct.sym 里不存在，用于探测 -release 是否生效
    println("x".isBlank)
  }
}
