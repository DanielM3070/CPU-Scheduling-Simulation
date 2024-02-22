import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.regex.Pattern;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

public class Main {

    static boolean useDebug = true;

    static void debug(Object... args) {
        if (useDebug) {
            for (Object arg : args) {
                System.out.print(arg + " ");
            }
            System.out.println();
        }
    }

    static class Stats {
        int arriveTime;
        int serviceTime = 0;
        Integer startTime = null;
        Integer finishTime = null;
        int totalResponseTime = 0;
        int numResponseTimes = 0;
        Integer lastReady = null;
        public int priorityLevel;
        public int remainingTime;

        Stats(int arriveTime) {
            this.arriveTime = arriveTime;
        }

        int turnaroundTime() {
            if(finishTime == null)
                return 0;
                
            return finishTime - arriveTime;
        }

        double avgResponseTime() {
            if (numResponseTimes == 0) {
                return 0;
            } else {
                return (double) (totalResponseTime / numResponseTimes);
            }
        }
    }

    static class Process {
        static List<Process> procs = new ArrayList<>();
        Stats stats = null; // To be added by scheduler
        int pid;
        int arrive;
        List<Integer> activities;
        public int estimatedServiceTime;

        Process(int pid, int arrive, List<Integer> activities) {
            this.pid = pid;
            this.arrive = arrive;
            this.activities = activities;
        }

        @Override
        public String toString() {
            return "Proccess " + pid + ", Arrive " + stats.arriveTime + ": " + activities;
        }
    }

    static final int ARRIVAL = 0;
    static final int UNBLOCK = 1;

    static final String[] EVENT_TYPE = { "ARRIVAL", "UNBLOCK" };

    static class Event implements Comparable<Event> {
        int type;
        Process process;
        int time;

        Event(int etype, Process process, int time) {
            this.type = etype;
            this.process = process;
            this.time = time;
        }

        @Override
        public int compareTo(Event other) {
            if (this.time == other.time) {
                // Break Tie with event type
                if (this.type == other.type) {
                    // Break type tie by pid
                    return Integer.compare(this.process.pid, other.process.pid);
                } else {
                    return Integer.compare(this.type, other.type);
                }
            } else {
                return Integer.compare(this.time, other.time);
            }
        }

        @Override
        public String toString() {
            return "At time " + time + ", " + EVENT_TYPE[type] + " Event for Process " + process.pid;
        }
    }

    static class EventQueue {
        PriorityQueue<Event> queue = new PriorityQueue<>();
        boolean dirty = false;

        void push(Event item) {
            if (item instanceof Event) {
                queue.add(item);
                dirty = true;
            } else {
                throw new IllegalArgumentException("Only Events allowed in EventQueue");
            }
        }

        private void prepareLookup(String operation) {
            if (queue.isEmpty()) {
                throw new RuntimeException(operation + " on empty EventQueue");
            }
            if (dirty) {
                queue = new PriorityQueue<>(queue);
                dirty = false;
            }
        }

        Event pop() {
            prepareLookup("Pop");
            return queue.poll();
        }

        Event peek() {
            prepareLookup("Peek");
            return queue.peek();
        }

        boolean empty() {
            return queue.isEmpty();
        }

        boolean hasEvent() {
            return !queue.isEmpty();
        }

        @Override
        public String toString() {
            StringBuilder tmp = new StringBuilder("EventQueue(");
            if (!queue.isEmpty()) {
                tmp.append(queue.peek());
            }
            for (Event e : queue) {
                tmp.append("; ").append(e);
            }
            tmp.append(")");
            return tmp.toString();
        }
    }

    static List<Process> parseProcessFile(String procFile) throws IOException {
        List<Process> procs = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(procFile))) {
            String line;
            int lineNumber = 1;
            while ((line = reader.readLine()) != null) {
                String[] tmp = line.split("\\s+");
                // Make sure there enough values on the line
                if (tmp.length < 2) {
                    throw new IllegalArgumentException(
                            "Process missing activities and possibly the arrival time at line " + lineNumber);
                }
                // Check to make sure there is a final CPU activity
                // We assume the first activity is CPU, and it alternates from there.
                // This means there must be an odd number of activities or an even number
                // of ints on the line (the first being arrival time)
                if (tmp.length % 2 == 1) {
                    throw new IllegalArgumentException("Process with no final CPU activity at line " + lineNumber);
                }
                // Check to make sure each activity, represented by a duration,
                // is an integer, and then convert it.
                for (int i = 0; i < tmp.length; i++) {
                    if (!Pattern.matches("\\d+", tmp[i])) {
                        throw new IllegalArgumentException("Invalid process on line " + lineNumber);
                    } else {
                        tmp[i] = Integer.toString(Integer.parseInt(tmp[i]));
                    }
                }
                procs.add(new Process(lineNumber - 1, Integer.parseInt(tmp[0]), parseActivities(tmp, 1)));
                lineNumber++;
            }
        }
        return procs;
    }

    private static List<Integer> parseActivities(String[] tmp, int start) {
        List<Integer> activities = new ArrayList<>();
        for (int i = start; i < tmp.length; i++) {
            activities.add(Integer.parseInt(tmp[i]));
        }
        return activities;
    }

    static Event parseEvent(String[] split, int lineNumber) {
        int type;
        switch (split[0].toUpperCase()) {
            case "ARRIVAL":
                type = ARRIVAL;
                break;
            case "UNBLOCK":
                type = UNBLOCK;
                break;
            default:
                throw new IllegalArgumentException("Invalid event type at line " + lineNumber);
        }
        Process process = Process.procs.get(Integer.parseInt(split[1]));
        int time = Integer.parseInt(split[2]);
        return new Event(type, process, time);
    }

    static class FCFSSched {
        List<Process> procs;
        List<Process> rq;
        int clock;
        int runningTime;
        Process running;
        EventQueue eventQueue;

        FCFSSched(List<Process> procs) {
            this.procs = procs;
            this.rq = new ArrayList<>();
            this.clock = 0;
            this.runningTime = 0;
            this.running = null;
            this.eventQueue = new EventQueue();
            for (Process p : this.procs) {
                eventQueue.push(new Event(ARRIVAL, p, p.arrive));
                p.stats = new Stats(p.arrive);
            }
        }

        void debug(String msg) {
            System.out.println("[" + clock + "] " + msg);
        }

        void runSim() {
            while (running != null || eventQueue.hasEvent()) {
                int nextEvent = eventQueue.hasEvent() ? eventQueue.peek().time : -1;
                if (running != null && (nextEvent == -1 || nextEvent > runningTime + clock)) {
                    clock += runningTime;
                    runningTime = 0;
                } else {
                    if (running != null) {
                        runningTime -= nextEvent - clock;
                    }
                    clock = nextEvent;
                }
                if (running != null && runningTime == 0) {
                    if (running.activities.isEmpty()) {
                        debug("Process " + running.pid + " is exiting");
                        running.stats.finishTime = clock;
                    } else {
                        int time = running.activities.remove(0);
                        debug("Process " + running.pid + " is blocking for " + time + " time units");
                        eventQueue.push(new Event(UNBLOCK, running, clock + time));
                    }
                    running = null;
                }
                // Do all events that happen at the current time
                while (eventQueue.hasEvent() && eventQueue.peek().time == clock) {
                    Event e = eventQueue.pop();
                    Process p = e.process;
                    if (e.type == ARRIVAL) {
                        debug("Process " + p.pid + " arrives");
                        rq.add(p);
                        p.stats.lastReady = clock;
                        // addednew
                        p.stats.totalResponseTime += (clock - p.stats.lastReady); // Update totalResponseTime
                    } else { // e.type == UNBLOCK
                        debug("Process " + p.pid + " unblocks");
                        rq.add(p);
                        p.stats.lastReady = clock;
                    }
                }
                if (running == null && !rq.isEmpty()) {
                    // Dispatch next process
                    debug("Current Ready Queue: " + rq);
                    Process p = rq.remove(0);
                    p.stats.totalResponseTime += (clock - p.stats.lastReady);
                    p.stats.numResponseTimes++;
                    int cpuTime = p.activities.remove(0);
                    p.stats.serviceTime += cpuTime;
                    if (p.stats.startTime == null) {
                        p.stats.startTime = clock;
                    }
                    runningTime = cpuTime;
                    running = p;
                    debug("Dispatching process " + p.pid);
                }
            }
        }
    }

    // make class for RR
    static class RRSched {
        List<Process> procs;
        List<Process> rq;
        int clock;
        int runningTime;
        Process running;
        EventQueue eventQueue;
        int quantum;

        RRSched(List<Process> procs, int quantum) {
            this.procs = procs;
            this.rq = new ArrayList<>();
            this.clock = 0;
            this.runningTime = 0;
            this.running = null;
            this.eventQueue = new EventQueue();
            this.quantum = quantum;
            for (Process p : this.procs) {
                eventQueue.push(new Event(ARRIVAL, p, p.arrive));
                p.stats = new Stats(p.arrive);
            }
        }

        void debug(String msg) {
            System.out.println("[" + clock + "] " + msg);
        }

        void runSim() {
            while (running != null || eventQueue.hasEvent()) {
                int nextEvent = eventQueue.hasEvent() ? eventQueue.peek().time : -1;
                if (running != null && (nextEvent == -1 || nextEvent > clock + Math.min(quantum, runningTime))) {
                    clock += Math.min(quantum, runningTime);
                    runningTime -= Math.min(quantum, runningTime);
                } else {
                    if (running != null) {
                        runningTime -= nextEvent - clock;
                    }
                    clock = nextEvent;
                }

                if (running != null && (runningTime == 0 || running.activities.isEmpty())) {
                    if (running.activities.isEmpty()) {
                        debug("Process " + running.pid + " is exiting");
                        running.stats.finishTime = clock;
                    } else {
                        debug("Process " + running.pid + " Timed out");
                        rq.add(running);
                    }
                    running = null;
                }

                while (eventQueue.hasEvent() && eventQueue.peek().time == clock) {
                    Event e = eventQueue.pop();
                    Process p = e.process;
                    if (e.type == ARRIVAL) {
                        debug("Process " + p.pid + " arrives");
                        rq.add(p);
                        p.stats.lastReady = clock;
                    } else { // e.type == UNBLOCK
                        debug("Process " + p.pid + " unblocks");
                        rq.add(p);
                        p.stats.lastReady = clock;
                    }
                }

                if (running == null && !rq.isEmpty()) {
                    // Dispatch next process
                    debug("Current Ready Queue: " + rq);
                    Process p = rq.remove(0);
                    p.stats.totalResponseTime += (clock - p.stats.lastReady);
                    p.stats.numResponseTimes++;
                    int cpuTime = p.activities.isEmpty() ? 0 : Math.min(p.activities.get(0), quantum);
                    if (cpuTime == p.activities.get(0)) {
                        p.activities.remove(0); // Remove the CPU burst if it's going to finish
                    } else {
                        p.activities.set(0, p.activities.get(0) - cpuTime); // Reduce the remaining CPU burst
                    }
                    p.stats.serviceTime += cpuTime;
                    if (p.stats.startTime == null) {
                        p.stats.startTime = clock;
                    }
                    runningTime = cpuTime;
                    running = p;
                    debug("Dispatching process " + p.pid);
                }
            }
        }

    }

    static class SPNSched {
        List<Process> procs;
        List<Process> rq;
        int clock;
        int runningTime;
        Process running;
        EventQueue eventQueue;
        boolean serviceGiven;
        double alpha;

        SPNSched(List<Process> procs, boolean serviceGiven, double alpha) {
            this.procs = procs;
            this.rq = new ArrayList<>();
            this.clock = 0;
            this.runningTime = 0;
            this.running = null;
            this.eventQueue = new EventQueue();
            this.serviceGiven = serviceGiven;
            this.alpha = alpha;
            for (Process p : this.procs) {
                eventQueue.push(new Event(ARRIVAL, p, p.arrive));
                p.stats = new Stats(p.arrive);
            }
        }

        void debug(String msg) {
            System.out.println("[" + clock + "] " + msg);
        }

        // When the service given key is true, the required service time is estimated as
        // the duration of the first CPU activity for the processes.
        // If service given is false then the exponential average is used to estimate
        // the required service time.You may use the first CPU activity as S1 (initial
        // estimate).
        void runSim() {
            while (running != null || eventQueue.hasEvent()) {
                int nextEvent = eventQueue.hasEvent() ? eventQueue.peek().time : -1;
                if (running != null && (nextEvent == -1 || nextEvent > clock + runningTime)) {
                    clock += runningTime;
                    runningTime = 0;
                } else {
                    if (running != null) {
                        runningTime -= nextEvent - clock;
                    }
                    clock = nextEvent;
                }
                if (running != null && runningTime == 0) {
                    if (running.activities.isEmpty()) {
                        debug("Process " + running.pid + " is exiting");
                        running.stats.finishTime = clock;
                    } else {
                        int time = running.activities.remove(0);
                        debug("Process " + running.pid + " is blocking for " + time + " time units");
                        eventQueue.push(new Event(UNBLOCK, running, clock + time));
                    }
                    running = null;
                }
                // Do all events that happen at the current time
                while (eventQueue.hasEvent() && eventQueue.peek().time == clock) {
                    Event e = eventQueue.pop();
                    Process p = e.process;
                    if (e.type == ARRIVAL) {
                        debug("Process " + p.pid + " arrives");
                        rq.add(p);
                        p.stats.lastReady = clock;
                        // addednew
                        p.stats.totalResponseTime += (clock - p.stats.lastReady); // Update totalResponseTime
                    } else { // e.type == UNBLOCK
                        debug("Process " + p.pid + " unblocks");
                        rq.add(p);
                        p.stats.lastReady = clock;
                    }
                }
                if (running == null && !rq.isEmpty()) {
                    // Dispatch next process
                    debug("Current Ready Queue: " + rq);
                    Process p = rq.remove(0);
                    p.stats.totalResponseTime += (clock - p.stats.lastReady);
                    p.stats.numResponseTimes++;
                    int cpuTime = 0;
                    if (serviceGiven) {
                        // When the service given key is true, the required service time is estimated as
                        // the duration of the first CPU activity for the processes.
                        if (p.estimatedServiceTime == 0) {
                            p.estimatedServiceTime = p.activities.get(0);
                        }
                        cpuTime = p.estimatedServiceTime;

                    } else {
                        // If service given is false then the exponential average is used to estimate
                        // the required service time.You may use the first CPU activity as S1 (initial
                        // estimate).
                        // use alpha as the weight factor in exponential averaging
                        if (p.estimatedServiceTime == 0) {
                            p.estimatedServiceTime = p.activities.get(0);
                        }
                        // make sure to update the estimated service time for the next time
                        if (p.activities.size() > 0
                                && p.activities.get(0) < p.estimatedServiceTime) {
                            p.estimatedServiceTime = p.activities.get(0);
                        } else {
                            // make sure that p.activities is not empty
                            if (p.activities.size() > 0) {
                                p.estimatedServiceTime = (int) (alpha * p.activities.get(0)
                                        + (1 - alpha) * p.estimatedServiceTime);
                            }
                        }
                        cpuTime = p.estimatedServiceTime;

                    }
                    p.stats.serviceTime += cpuTime;
                    if (p.stats.startTime == null) {
                        p.stats.startTime = clock;
                    }
                    runningTime = cpuTime;
                    running = p;
                    debug("Dispatching process " + p.pid);
                }
            }
        }

    }

    // class for FEEDBACKSched
    static class FEEDBACKSched {
        List<Process> procs;
        List<List<Process>> multiLevelQueue;
        int clock;
        Process running;
        EventQueue eventQueue;
        int[] quantum;
        int numPriorities;

        FEEDBACKSched(List<Process> procs, int numPriorities, int baseQuantum) {
            this.procs = procs;
            this.multiLevelQueue = new ArrayList<>();
            for (int i = 0; i < numPriorities; i++) {
                this.multiLevelQueue.add(new ArrayList<>());
            }
            this.clock = 0;
            this.running = null;
            this.eventQueue = new EventQueue();
            this.numPriorities = numPriorities;
            this.quantum = new int[numPriorities];
            for (int i = 0; i < numPriorities; i++) {
                this.quantum[i] = baseQuantum * (i + 1); // Increment quantum for each level
            }
            for (Process p : this.procs) {
                eventQueue.push(new Event(ARRIVAL, p, p.arrive));
                p.stats = new Stats(p.arrive);
                p.stats.remainingTime = p.estimatedServiceTime; // Assuming estimated service time is provided
                p.stats.priorityLevel = 0; // Start each process at the highest priority level
            }
        }

        void debug(String msg) {
            System.out.println("[" + clock + "] " + msg);
        }

        void runSim() {
            while (!allQueuesEmpty() || eventQueue.hasEvent()) {
                int nextEvent = eventQueue.hasEvent() ? eventQueue.peek().time : -1;
                if (running != null && (nextEvent == -1 || nextEvent > clock)) {
                    executeRunningProcess();
                } else {
                    clock = nextEvent;
                }

                processEvents();
                if (running == null) {
                    dispatchNextProcess();
                }
            }
        }

        private void executeRunningProcess() {
            int level = running.stats.priorityLevel;
            int timeSlice = Math.min(quantum[level], running.stats.remainingTime);
            clock += timeSlice;
            running.stats.remainingTime -= timeSlice;

            if (running.stats.remainingTime <= 0) {
                finishProcess();
            } else {
                int nextLevel = Math.min(level + 1, numPriorities - 1);
                multiLevelQueue.get(nextLevel).add(running);
                running = null;
            }
        }

        private void processEvents() {
            while (eventQueue.hasEvent() && eventQueue.peek().time == clock) {
                Event e = eventQueue.pop();
                Process p = e.process;
                if (e.type == ARRIVAL || e.type == UNBLOCK) {
                    debug("Process " + p.pid + (e.type == ARRIVAL ? " arrives" : " unblocks"));
                    p.stats.lastReady = clock;
                    multiLevelQueue.get(0).add(p); // Add to the highest priority queue
                }
            }
        }

        private void dispatchNextProcess() {
            for (List<Process> queue : multiLevelQueue) {
                if (!queue.isEmpty()) {
                    running = queue.remove(0);
                    debug("Dispatching process " + running.pid);
                    break;
                }
            }
        }

        private void finishProcess() {
            debug("Process " + running.pid + " is finishing");
            running.stats.finishTime = clock;
            running = null;
        }

        private boolean allQueuesEmpty() {
            for (List<Process> queue : multiLevelQueue) {
                if (!queue.isEmpty()) {
                    return false;
                }
            }
            return true;
        }
        
    }

    

    static void printProcessStats(Process p) {

        if(p.stats.finishTime == null)
            p.stats.finishTime = 0;
            
        System.out.println("Process " + p.pid);
        System.out.println("    Start Time: " + p.stats.startTime);
        System.out.println("    Finish Time: " + p.stats.finishTime);
        System.out.println("    Service Time: " + p.stats.serviceTime);
        System.out.println("    Turnaround Time: " + p.stats.turnaroundTime());
        System.out.println(
                "    Normalized Turnaround Time: " + ((double) p.stats.turnaroundTime() / p.stats.serviceTime));
        System.out.println("    Average Response Time: " + p.stats.avgResponseTime());
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.out.println("Invalid number of arguments");
            System.exit(1);
        }

        String schedulerFile = args[0];
        String processFile = args[1];
        // Read and parse the scheduler file
        String schedulerType = "";
        int quantum = 0; // Default quantum for RR

        // for Shortest Process Next
        boolean serviceGiven = false;
        double alpha = 0.5;

        // An integer representing the number of levels of priorities
        int numPriorities = 0;

        if (useDebug) {
            System.out.println("Scheduler File: " + schedulerFile);
            System.out.println("Process File: " + processFile);
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(schedulerFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // first line is the scheduler type
                schedulerType = line;

                if (schedulerType.equals("RR")) {
                    // second line is the quantum
                    // will look like quantum=10
                    line = reader.readLine();
                    String[] split = line.split("=");
                    quantum = Integer.parseInt(split[1]);
                } else if (schedulerType.equals("SPN")) {
                    // second line is service given
                    // will look like service_given=false
                    line = reader.readLine();
                    String[] split = line.split("=");
                    serviceGiven = Boolean.parseBoolean(split[1]);

                    // third line is the alpha
                    // will look like alpha=0.5
                    line = reader.readLine();
                    split = line.split("=");
                    alpha = Double.parseDouble(split[1]);
                } else if (schedulerType.equals("FEEDBACK")) {
                    // second line is the quantum
                    // will look like quantum=10
                    line = reader.readLine();
                    String[] split = line.split("=");

                    // first line is numPriorities
                    numPriorities = Integer.parseInt(split[1]);

                    // second line is the quantum
                    line = reader.readLine();
                    split = line.split("=");
                    quantum = Integer.parseInt(split[1]);
                } else {
                    // do nothing
                }
            }
        }

        List<Process> procs = parseProcessFile(processFile);
        Process.procs = procs;

        if (useDebug) {
            System.out.println("Scheduler Type: " + schedulerType);
            System.out.println("Quantum: " + quantum);
            System.out.println("Service Given: " + serviceGiven);
            System.out.println("Alpha: " + alpha);
            System.out.println("Num Priorities: " + numPriorities);
        }
        if (schedulerType.equals("RR")) {
            // create a new RR scheduler
            RRSched sim = new RRSched(procs, quantum);
            sim.runSim();
        } else if (schedulerType.equals("FCFS")) {
            FCFSSched sim = new FCFSSched(procs);
            sim.runSim();
        } else if (schedulerType.equals("SPN")) {
            SPNSched sim = new SPNSched(procs, serviceGiven, alpha);
            sim.runSim();
        } else if (schedulerType.equals("FEEDBACK")) {
            FEEDBACKSched sim = new FEEDBACKSched(procs, numPriorities, quantum);
            sim.runSim();
        } else {
            System.out.println("Invalid scheduler type");
            System.exit(1);
        }

        if (useDebug) {
            System.out.println("Done");
        }

        for (Process p : procs) {
            printProcessStats(p);
        }

        int mtt = 0;
        double mntt = 0;
        double mart = 0;
        for (Process p : procs) {
            mtt += p.stats.turnaroundTime();
            mntt += ((double) p.stats.turnaroundTime() / p.stats.serviceTime);
            mart += p.stats.avgResponseTime();
        }

        System.out.println("--------------------------------------------------------------------------------");
        System.out.println("Mean Turnaround Time: " + (mtt / procs.size()));
        System.out.println("Mean Normalized Turnaround Time: " + (mntt / procs.size()));
        System.out.println("Mean Response Time: " + (mart / procs.size()));
    }
}