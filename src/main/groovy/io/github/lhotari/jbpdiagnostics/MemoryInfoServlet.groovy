package io.github.lhotari.jbpdiagnostics

import com.sun.management.UnixOperatingSystemMXBean
import org.joda.time.DateTime
import org.joda.time.Period
import org.joda.time.format.DateTimeFormatter
import org.joda.time.format.ISODateTimeFormat
import org.joda.time.format.ISOPeriodFormat

import javax.servlet.GenericServlet
import javax.servlet.ServletException
import javax.servlet.ServletRequest
import javax.servlet.ServletResponse
import javax.servlet.annotation.WebServlet
import javax.servlet.http.HttpServletResponse
import java.lang.management.ManagementFactory
import java.lang.management.MemoryMXBean
import java.lang.management.MemoryPoolMXBean
import java.lang.management.MemoryUsage
import java.lang.management.OperatingSystemMXBean
import java.lang.management.RuntimeMXBean

/**
 * Servlet that shows memory usage information and returns it as a response
 *
 * Created by lari on 03/03/15.
 */
@WebServlet("/meminfo")
class MemoryInfoServlet extends GenericServlet {
    static int BYTES_PER_MB = 1024 * 1000
    static int KB_PER_MB = 1024

    @Override
    void service(ServletRequest req, ServletResponse res) throws ServletException, IOException {
        def out=((HttpServletResponse)res).getWriter()
        if(AccessControlService.instance.isOperationAllowed(req, "meminfo")) {
            doMemInfo(out)
        } else {
            out << "NOT OK"
        }
        out.close()
    }

    protected synchronized void doMemInfo(PrintWriter out) {

        DateTime dt = new DateTime();
        DateTimeFormatter fmt = ISODateTimeFormat.dateTime();
        out << fmt.print(dt) << '\n'

        RuntimeMXBean runtimeMXBean = ManagementFactory.getRuntimeMXBean()
        out << "JVM start time "
        out << fmt.print(new DateTime(runtimeMXBean.getStartTime()))
        out << "\n"
        out << "JVM uptime " <<  ISOPeriodFormat.standard().print(new Period(runtimeMXBean.getUptime())) << "\n\n"

        OperatingSystemMXBean osMXBean = ManagementFactory.getOperatingSystemMXBean()
        out << "CPU count: ${osMXBean.availableProcessors}\n"
        if(osMXBean instanceof UnixOperatingSystemMXBean) {
            UnixOperatingSystemMXBean unixOsMXBean = (UnixOperatingSystemMXBean)osMXBean
            out << "Open files: ${unixOsMXBean.openFileDescriptorCount} / ${unixOsMXBean.maxFileDescriptorCount}\n"
            out << "Committed virtual memory: ${toMB(unixOsMXBean.committedVirtualMemorySize)}M\n"
        }

        out << "\n"
        out << "JVM memory usage\n"
        MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean()
        MemoryUsage heap = memoryMXBean.getHeapMemoryUsage();
        out << formatMemoryUsage("Heap", memoryMXBean.getHeapMemoryUsage()) << "\n";
        out << formatMemoryUsage("Non-Heap", memoryMXBean.getNonHeapMemoryUsage())  << "\n";
        out << "\nMemory pools\n"
        def memPoolBeans = ManagementFactory.getMemoryPoolMXBeans().sort { it.type }
        for(MemoryPoolMXBean memoryPoolMXBean in memPoolBeans) {
            out << formatMemoryUsage(memoryPoolMXBean.name, memoryPoolMXBean.usage) << "\n"
            out << formatMemoryUsage(memoryPoolMXBean.name + " peak", memoryPoolMXBean.peakUsage) << "\n"
        }
        out << "\n"
        printOSMemory(out)
    }

    String formatMemoryUsage(String name, MemoryUsage memoryUsage) {
        String.format("%-35s used: %4dM committed: %4dM max: %4dM", name, toMB(memoryUsage.getUsed()), toMB(memoryUsage.getCommitted()), toMB(memoryUsage.getMax()));
    }

    void printOSMemory(PrintWriter out) {
        int currentPid = DiagUtils.getCurrentProcessId()

        def psfields = ["pid","ppid","rss","vsz","pmem","cpu","cputime","comm"]
        def psSortOption = DiagUtils.isMacOs() ? "-m" : "--sort=-rss"
        def psCommand = ["/bin/ps", "-o", psfields.join(','), "-e", psSortOption]
        Process p = psCommand.execute()
        def psOutput=p.text
        if(p.waitFor() == 0 && psOutput) {
            printMemSummary(out, psOutput, psfields.size(), currentPid)
            if(new File("/usr/bin/free").exists()) {
                def freeCmd = "/usr/bin/free -m"
                def freeOutput = freeCmd.execute().text
                if(freeOutput) {
                    out << "\n\n\$ $freeCmd\n" << freeOutput << "\n"
                }
            }
            out << "\$ ${psCommand.join(' ')}\n\n"
            out << psOutput
            if(new File("/usr/bin/pmap").exists()) {
                def pmapCmd = "/usr/bin/pmap -x $currentPid"
                def pmapOutput = pmapCmd.execute().text
                if(pmapOutput) {
                    out << "\n\n\$ $pmapCmd\n" << pmapOutput << "\n"
                }
            }
            out << "\n"
            def procStatusFile = new File("/proc/$currentPid/status")
            if(procStatusFile.exists()) {
                out << procStatusFile.text
            }
        }

    }

    protected void printMemSummary(PrintWriter out, String psOutput, int numberOfPsFields, int currentPid) {
        int rssTotal = 0
        int currentRss = 0
        int currentVsz = 0
        psOutput.eachLine { String line, int linenum ->
            if (linenum > 0) {
                def fields = line.trim().split(/\s+/, numberOfPsFields)
                int rss = fields[2] as int
                int vsz = fields[3] as int
                if (currentPid.toString() == fields[0]) {
                    currentRss = rss
                    currentVsz = vsz
                }
                rssTotal += rss
            }
        }

        out << "OS memory report\n\n"
        out << "Memory usage of current process (pid $currentPid):\n"
        out << "Resident set size (rss): ${kbToMB(currentRss)}M\n"
        out << "Virtual size (vsz): ${kbToMB(currentVsz)}M\n"
        out << "\n"
        out << "Total RSS of all listed processes: ${kbToMB(rssTotal)}M - ${rssTotal}K\n"
        out << "\n"
    }

    int toMB(Number number) {
        (number / BYTES_PER_MB) as Integer
    }

    int kbToMB(Number number) {
        (number / KB_PER_MB) as Integer
    }
}

