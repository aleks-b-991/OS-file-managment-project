/*
 * =====================================================================================
 *  FileManagement.java
 * =====================================================================================
 *  Project : Emulating how an Operating System moves a file from disk into memory
 *
 *  What this program does
 *  ----------------------
 *  Files are stored on a simulated hard disk using LINKED storage allocation: the
 *  directory entry stores only the FIRST block of the file, and every disk block
 *  stores a pointer to the NEXT block of the same file (-1 marks the end of the chain).
 *
 *  When a file has to be brought into memory, the FileManagement class:
 *    1. Collects every fragment (disk block) of the file by following the chain of
 *       "next" pointers on the disk                       -> collectFileFragments()
 *    2. Calculates the memory space (number of pages) the file needs. The space is
 *       equal to the size of the file only (no metadata)  -> calculateRequiredMemory()
 *    3. Allocates page frames in memory for the file. If memory is full, it frees
 *       space by swapping SLEEPING jobs out of memory (the memory-management
 *       technique from Assignment 1)                      -> allocateFileInMemory()
 *                                                           liberateMemory()
 *    4. Keeps the file in memory until its "stay until" interval, then releases it
 *       and brings swapped-out jobs back if there is room -> releaseExpiredFiles()
 *                                                           swapInJobs()
 *
 *  Memory model : paged memory. Total memory and page size are configurable
 *                 (assignment use case: 20 KB memory, 1 KB pages = 20 frames).
 *  Time model   : discrete time intervals 1, 2, 3, ...
 *
 *  How to compile and run
 *  ----------------------
 *      javac FileManagement.java
 *      java FileManagement                 (runs the assignment case + 5 other test files)
 *      java FileManagement custom <fileSizeKB> <loadInterval> <stayUntil> <block1> <block2> ...
 *          e.g.  java FileManagement custom 8 12 16 28 5 12 13 1 4
 * =====================================================================================
 */

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/* =====================================================================================
 *  Supporting data classes
 * ===================================================================================== */

/** Possible states of a job (process) during the simulation. */
enum JobState {
    /** The job's start time has not been reached yet. */
    NOT_ARRIVED,
    /** The job has arrived but there was not enough memory to load it yet. */
    WAITING,
    /** The job is in memory and executing. */
    RUNNING,
    /** The job finished its execution interval and sleeps; it still occupies memory. */
    SLEEPING,
    /** The job was sleeping and was moved out to disk to free memory. */
    SWAPPED_OUT
}

/** Possible states of a request to load a file into memory. */
enum FileRequestState {
    /** The load interval has not been reached yet. */
    NOT_REQUESTED,
    /** The file was requested but could not be loaded yet (not enough memory). */
    PENDING,
    /** The file is loaded in memory. */
    IN_MEMORY,
    /** The file's stay period ended and its memory was released. */
    RELEASED,
    /** The file could not be loaded (too big, corrupted chain, or time window missed). */
    REJECTED
}

/** A job (process) from the job table, e.g. "Job 1, start 1, size 2 KB, runs 7 intervals". */
class Job {
    /** Job ID from the job table. */
    final int id;
    /** Interval at which the job asks to be loaded into memory (its "Start Time"). */
    final int arrivalTime;
    /** Memory the job requires, in KB. */
    final int sizeKB;
    /** Number of intervals the job executes before going to sleep. */
    final int executionInterval;

    /** Interval at which the job was actually loaded (can be later than arrivalTime). */
    int loadTime = -1;
    /** Interval at which the job started sleeping (used to pick swap-out victims). */
    int sleepSince = -1;
    /** Interval at which the job was last swapped out (it is not swapped back in the same interval). */
    int swappedOutAt = -1;
    /** Current state of the job. */
    JobState state = JobState.NOT_ARRIVED;
    /** Page frames the job currently occupies in memory. */
    final List<Integer> frames = new ArrayList<>();

    /**
     * Creates a job.
     * @param id                job ID
     * @param arrivalTime       interval at which the job starts
     * @param sizeKB            required memory in KB
     * @param executionInterval number of intervals the job runs
     */
    Job(int id, int arrivalTime, int sizeKB, int executionInterval) {
        this.id = id;
        this.arrivalTime = arrivalTime;
        this.sizeKB = sizeKB;
        this.executionInterval = executionInterval;
    }

    /** @return the label written in the memory map for this job, e.g. "J3". */
    String label() {
        return "J" + id;
    }
}

/** One block (sector) of the simulated hard disk. */
class DiskBlock {
    /** Name of the file that owns this block, or null if the block is free. */
    String owner = null;
    /** How many KB of file data are stored in this block (the last block may be partly used). */
    int usedKB = 0;
    /** Linked allocation pointer: number of the next block of the file, -1 = end of file. */
    int next = -1;
}

/** Directory entry of a file. With linked allocation only the first block is recorded. */
class DirectoryEntry {
    /** File name. */
    final String name;
    /** File size in KB, as recorded in the directory. */
    final int sizeKB;
    /** First disk block of the file (head of the linked list). */
    final int startBlock;

    /**
     * @param name       file name
     * @param sizeKB     file size in KB
     * @param startBlock first block of the file's chain
     */
    DirectoryEntry(String name, int sizeKB, int startBlock) {
        this.name = name;
        this.sizeKB = sizeKB;
        this.startBlock = startBlock;
    }
}

/** A fragment of a file found while following its chain of disk blocks. */
class Fragment {
    /** Position of the fragment inside the file (1 = first fragment). */
    final int order;
    /** Disk block number where the fragment is stored. */
    final int blockNumber;
    /** Amount of file data in this fragment, in KB. */
    final int sizeKB;

    /**
     * @param order       position of the fragment in the file
     * @param blockNumber disk block number
     * @param sizeKB      data stored in the block (KB)
     */
    Fragment(int order, int blockNumber, int sizeKB) {
        this.order = order;
        this.blockNumber = blockNumber;
        this.sizeKB = sizeKB;
    }
}

/** A simulated hard disk that stores files with linked allocation. */
class HardDisk {
    /** Size of one disk block in KB. */
    final int blockSizeKB;
    /** All the blocks of the disk, indexed by block number. */
    final DiskBlock[] blocks;
    /** The disk directory: file name -> directory entry. */
    final Map<String, DirectoryEntry> directory = new LinkedHashMap<>();

    /**
     * Creates an empty disk.
     * @param numberOfBlocks number of blocks on the disk
     * @param blockSizeKB    size of each block in KB
     */
    HardDisk(int numberOfBlocks, int blockSizeKB) {
        if (numberOfBlocks <= 0 || blockSizeKB <= 0) {
            throw new IllegalArgumentException("Disk size and block size must be positive.");
        }
        this.blockSizeKB = blockSizeKB;
        this.blocks = new DiskBlock[numberOfBlocks];
        for (int i = 0; i < numberOfBlocks; i++) {
            blocks[i] = new DiskBlock();
        }
    }

    /**
     * Writes a file to disk using linked allocation: the blocks are chained in the
     * order given, the directory only stores the first block.
     * @param name       file name
     * @param sizeKB     file size in KB (recorded in the directory)
     * @param blockChain disk blocks allocated to the file, in file order
     */
    void storeFile(String name, int sizeKB, int[] blockChain) {
        if (blockChain.length == 0) {
            throw new IllegalArgumentException("A file needs at least one disk block.");
        }
        if (directory.containsKey(name)) {
            throw new IllegalArgumentException("File '" + name + "' already exists on disk.");
        }
        for (int b : blockChain) {
            if (b < 0 || b >= blocks.length) {
                throw new IllegalArgumentException("Block " + b + " does not exist on this disk.");
            }
            if (blocks[b].owner != null) {
                throw new IllegalArgumentException("Block " + b + " is already used by '" + blocks[b].owner + "'.");
            }
        }
        int remainingKB = sizeKB;
        for (int i = 0; i < blockChain.length; i++) {
            DiskBlock block = blocks[blockChain[i]];
            block.owner = name;
            block.usedKB = Math.max(0, Math.min(blockSizeKB, remainingKB));
            remainingKB -= block.usedKB;
            block.next = (i + 1 < blockChain.length) ? blockChain[i + 1] : -1;
        }
        directory.put(name, new DirectoryEntry(name, sizeKB, blockChain[0]));
    }
}

/** A request to bring a file into memory during a window of intervals. */
class FileRequest {
    /** Name of the file on disk. */
    final String fileName;
    /** Short label used in the memory map, e.g. "F1". */
    final String label;
    /** Interval at which the file must be transferred into memory. */
    final int requestTime;
    /** Last interval during which the file must stay in memory. */
    final int stayUntil;

    /** Current state of the request. */
    FileRequestState state = FileRequestState.NOT_REQUESTED;
    /** Fragments collected from disk (filled on the first load attempt). */
    List<Fragment> fragments = null;
    /** Number of memory pages the file needs (filled on the first load attempt). */
    int requiredPages = -1;
    /** Interval at which the file was really loaded. */
    int loadedAt = -1;
    /** Page frames used by the file; index = page number of the file. */
    final List<Integer> frames = new ArrayList<>();

    /**
     * @param fileName    file to load
     * @param label       label for the memory map
     * @param requestTime interval at which the file is needed in memory
     * @param stayUntil   last interval the file must stay in memory
     */
    FileRequest(String fileName, String label, int requestTime, int stayUntil) {
        this.fileName = fileName;
        this.label = label;
        this.requestTime = requestTime;
        this.stayUntil = stayUntil;
    }
}

/* =====================================================================================
 *  The File Management class
 * ===================================================================================== */

/**
 * Emulates the part of an operating system that transfers files stored with linked
 * allocation from the hard disk into paged main memory, freeing memory if needed.
 */
public class FileManagement {

    /** Total size of main memory in KB. */
    private final int memorySizeKB;
    /** Size of one page / page frame in KB. */
    private final int pageSizeKB;
    /** Number of page frames in memory (memorySizeKB / pageSizeKB). */
    private final int numberOfFrames;
    /** Frame table: frameTable[f] = label of the owner of frame f ("J1", "F1"), or null if free. */
    private final String[] frameTable;
    /** The hard disk the files are read from. */
    private final HardDisk disk;
    /** Job table (processes competing for memory). */
    private final List<Job> jobs;
    /** Jobs that arrived but are still waiting for memory, in arrival order. */
    private final List<Job> waitingJobs = new ArrayList<>();
    /** All requests to load files into memory. */
    private final List<FileRequest> fileRequests = new ArrayList<>();

    /**
     * Creates the file manager.
     * @param memorySizeKB total memory in KB
     * @param pageSizeKB   page size in KB
     * @param disk         the hard disk that holds the files
     * @param jobs         the job table
     */
    public FileManagement(int memorySizeKB, int pageSizeKB, HardDisk disk, List<Job> jobs) {
        if (memorySizeKB <= 0 || pageSizeKB <= 0 || memorySizeKB % pageSizeKB != 0) {
            throw new IllegalArgumentException("Memory size must be a positive multiple of the page size.");
        }
        this.memorySizeKB = memorySizeKB;
        this.pageSizeKB = pageSizeKB;
        this.numberOfFrames = memorySizeKB / pageSizeKB;
        this.frameTable = new String[numberOfFrames];
        this.disk = disk;
        this.jobs = jobs;
    }

    /**
     * Registers a file that must be brought into memory.
     * @param fileName    name of the file (must exist on disk)
     * @param requestTime interval at which it must be transferred into memory
     * @param stayUntil   last interval it must stay in memory
     */
    public void addFileRequest(String fileName, int requestTime, int stayUntil) {
        if (stayUntil < requestTime) {
            throw new IllegalArgumentException("stayUntil must be >= requestTime for " + fileName);
        }
        fileRequests.add(new FileRequest(fileName, "F" + (fileRequests.size() + 1), requestTime, stayUntil));
    }

    /* ------------------------------------------------------------------ */
    /*  STEP 1 - Collect all fragments of the file from the hard disk      */
    /* ------------------------------------------------------------------ */

    /**
     * Follows the linked list of disk blocks of a file, starting from the block
     * recorded in the directory, and collects every fragment until the end marker (-1).
     * The chain is validated: invalid block numbers, blocks owned by another file
     * and loops in the chain are reported as errors.
     *
     * @param fileName name of the file
     * @return the fragments of the file, in file order
     * @throws IllegalStateException if the file is missing or its chain is corrupted
     */
    public List<Fragment> collectFileFragments(String fileName) {
        DirectoryEntry entry = disk.directory.get(fileName);
        if (entry == null) {
            throw new IllegalStateException("File '" + fileName + "' not found in the disk directory.");
        }
        List<Fragment> fragments = new ArrayList<>();
        Set<Integer> visited = new HashSet<>();   // detects loops in the chain
        int current = entry.startBlock;

        while (current != -1) {
            if (current < 0 || current >= disk.blocks.length) {
                throw new IllegalStateException("Corrupted chain: pointer to non-existent block " + current + ".");
            }
            if (!visited.add(current)) {
                throw new IllegalStateException("Corrupted chain: block " + current + " is visited twice (loop).");
            }
            DiskBlock block = disk.blocks[current];
            if (!fileName.equals(block.owner)) {
                throw new IllegalStateException("Corrupted chain: block " + current + " does not belong to '" + fileName + "'.");
            }
            fragments.add(new Fragment(fragments.size() + 1, current, block.usedKB));
            current = block.next;                 // follow the link to the next fragment
        }
        return fragments;
    }

    /* ------------------------------------------------------------------ */
    /*  STEP 2 - Calculate the memory space needed by the file             */
    /* ------------------------------------------------------------------ */

    /**
     * Calculates how many memory pages a file needs. The space equals the size of the
     * file only (no metadata). The size found on disk (sum of the fragments) is compared
     * with the size in the directory; if they differ a warning is printed and the larger
     * value is used, so the whole file always fits in the space reserved for it.
     *
     * @param fileName  name of the file
     * @param fragments fragments returned by collectFileFragments()
     * @return number of pages (frames) required
     */
    public int calculateRequiredMemory(String fileName, List<Fragment> fragments) {
        DirectoryEntry entry = disk.directory.get(fileName);
        int sizeOnDiskKB = 0;
        for (Fragment f : fragments) {
            sizeOnDiskKB += f.sizeKB;
        }
        if (sizeOnDiskKB != entry.sizeKB) {
            System.out.println("    WARNING: directory says " + entry.sizeKB + " KB but the chain of "
                    + fragments.size() + " block(s) holds " + sizeOnDiskKB + " KB. Using "
                    + Math.max(sizeOnDiskKB, entry.sizeKB) + " KB so the whole file fits.");
        }
        int fileSizeKB = Math.max(sizeOnDiskKB, entry.sizeKB);
        return ceilDiv(fileSizeKB, pageSizeKB);
    }

    /* ------------------------------------------------------------------ */
    /*  STEP 3 - Allocate memory for the file (freeing memory if needed)   */
    /* ------------------------------------------------------------------ */

    /**
     * Tries to load a file into memory at interval t. On the first attempt the
     * fragments are collected and the required space is calculated. If there are not
     * enough free frames, sleeping jobs are swapped out. If memory still cannot be
     * found, the request stays PENDING and is retried next interval.
     *
     * @param request the file request
     * @param t       current interval
     * @return true if the file is now in memory
     */
    public boolean allocateFileInMemory(FileRequest request, int t) {
        // Step 1 and 2 are done once, the first time the file is requested.
        if (request.fragments == null) {
            System.out.println("  [t=" + t + "] File '" + request.fileName + "' (" + request.label
                    + ") requested in memory until interval " + request.stayUntil + ".");
            try {
                request.fragments = collectFileFragments(request.fileName);
            } catch (IllegalStateException e) {
                System.out.println("    ERROR: " + e.getMessage() + " File rejected.");
                request.state = FileRequestState.REJECTED;
                return false;
            }
            printFragments(request.fragments);
            request.requiredPages = calculateRequiredMemory(request.fileName, request.fragments);
            System.out.println("    Required memory: " + request.requiredPages + " page(s) of "
                    + pageSizeKB + " KB = " + (request.requiredPages * pageSizeKB) + " KB.");

            if (request.requiredPages > numberOfFrames) {
                System.out.println("    ERROR: the file needs more than the whole memory ("
                        + memorySizeKB + " KB). File rejected.");
                request.state = FileRequestState.REJECTED;
                return false;
            }
        }

        int free = countFreeFrames();
        System.out.println("    Free frames: " + free + " / needed: " + request.requiredPages);
        if (free < request.requiredPages) {
            liberateMemory(request.requiredPages - free, t);
        }
        if (countFreeFrames() < request.requiredPages) {
            System.out.println("    Not enough memory even after swapping. File waits (retry next interval).");
            request.state = FileRequestState.PENDING;
            return false;
        }

        request.frames.addAll(allocateFrames(request.label, request.requiredPages));
        request.state = FileRequestState.IN_MEMORY;
        request.loadedAt = t;
        System.out.println("    File loaded. Page table of '" + request.fileName + "':");
        printFilePageTable(request);
        return true;
    }

    /**
     * Frees memory by swapping SLEEPING jobs out to disk (technique from Assignment 1).
     * Victims are chosen by "longest sleeping first" (earliest sleepSince, then lowest ID).
     * RUNNING jobs and files are never removed. If all sleeping jobs together cannot free
     * enough frames, nothing is swapped out (it would be useless).
     *
     * @param framesNeeded number of extra frames that must be freed
     * @param t            current interval
     * @return true if at least framesNeeded frames were freed
     */
    public boolean liberateMemory(int framesNeeded, int t) {
        List<Job> candidates = new ArrayList<>();
        int reclaimable = 0;
        for (Job j : jobs) {
            if (j.state == JobState.SLEEPING) {
                candidates.add(j);
                reclaimable += j.frames.size();
            }
        }
        if (reclaimable < framesNeeded) {
            System.out.println("    Sleeping jobs only hold " + reclaimable + " frame(s); "
                    + framesNeeded + " needed. No job swapped out.");
            return false;
        }
        candidates.sort(Comparator.comparingInt((Job j) -> j.sleepSince).thenComparingInt(j -> j.id));

        int freed = 0;
        for (Job victim : candidates) {
            if (freed >= framesNeeded) {
                break;
            }
            freed += victim.frames.size();
            swapOut(victim, t);
        }
        return freed >= framesNeeded;
    }

    /**
     * Moves a sleeping job out of memory to disk and frees its frames.
     * @param job the job to swap out
     * @param t   current interval
     */
    private void swapOut(Job job, int t) {
        System.out.println("    Swapping out " + job.label() + " (sleeping since t=" + job.sleepSince
                + ", frees " + job.frames.size() + " frame(s): " + job.frames + ")");
        freeFrames(job.frames);
        job.state = JobState.SWAPPED_OUT;
        job.swappedOutAt = t;
    }

    /* ------------------------------------------------------------------ */
    /*  STEP 4 - Release files and bring swapped jobs back                 */
    /* ------------------------------------------------------------------ */

    /**
     * Releases the memory of every file whose stay period is over (t > stayUntil) and
     * rejects pending files whose time window has passed.
     * @param t current interval
     */
    public void releaseExpiredFiles(int t) {
        for (FileRequest r : fileRequests) {
            if (r.state == FileRequestState.IN_MEMORY && t > r.stayUntil) {
                System.out.println("  [t=" + t + "] File '" + r.fileName + "' stay period ended (until t="
                        + r.stayUntil + "). Releasing frames " + r.frames + ".");
                freeFrames(r.frames);
                r.state = FileRequestState.RELEASED;
            } else if (r.state == FileRequestState.PENDING && t > r.stayUntil) {
                System.out.println("  [t=" + t + "] File '" + r.fileName
                        + "' could never be loaded in its window. Request rejected.");
                r.state = FileRequestState.REJECTED;
            }
        }
    }

    /**
     * Brings swapped-out jobs back into memory (in ID order) when there is room and
     * nothing else (job or file) is waiting for memory. A job is never swapped back in
     * the same interval it was swapped out (that would undo the swap for nothing).
     * @param t current interval
     */
    public void swapInJobs(int t) {
        if (!waitingJobs.isEmpty()) {
            return;
        }
        for (FileRequest r : fileRequests) {
            if (r.state == FileRequestState.PENDING) {
                return;
            }
        }
        for (Job j : jobs) {
            int pages = ceilDiv(j.sizeKB, pageSizeKB);
            if (j.state == JobState.SWAPPED_OUT && t > j.swappedOutAt && countFreeFrames() >= pages) {
                j.frames.addAll(allocateFrames(j.label(), pages));
                j.state = JobState.SLEEPING;
                j.sleepSince = t;
                System.out.println("  [t=" + t + "] Swapping " + j.label() + " back into memory, frames " + j.frames + ".");
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Job handling (needed to recreate the memory state of the use case) */
    /* ------------------------------------------------------------------ */

    /**
     * Moves RUNNING jobs whose execution interval is over to the SLEEPING state.
     * A job loaded at interval L with execution interval E runs during L .. L+E-1
     * and sleeps from L+E on (it keeps its memory).
     * @param t current interval
     */
    public void updateJobStates(int t) {
        for (Job j : jobs) {
            if (j.state == JobState.RUNNING && t >= j.loadTime + j.executionInterval) {
                j.state = JobState.SLEEPING;
                j.sleepSince = j.loadTime + j.executionInterval;
                System.out.println("  [t=" + t + "] " + j.label() + " finished its execution interval -> SLEEPING.");
            }
        }
    }

    /**
     * Adds jobs that arrive at interval t to the waiting list and loads every waiting
     * job that fits (swapping out sleeping jobs if necessary).
     * @param t current interval
     */
    public void loadJobs(int t) {
        for (Job j : jobs) {
            if (j.state == JobState.NOT_ARRIVED && j.arrivalTime == t) {
                j.state = JobState.WAITING;
                waitingJobs.add(j);
            }
        }
        Iterator<Job> it = waitingJobs.iterator();
        while (it.hasNext()) {
            Job j = it.next();
            int pages = ceilDiv(j.sizeKB, pageSizeKB);
            if (pages > numberOfFrames) {
                System.out.println("  [t=" + t + "] " + j.label() + " is larger than memory. Job rejected.");
                it.remove();
                continue;
            }
            if (countFreeFrames() < pages) {
                System.out.println("  [t=" + t + "] " + j.label() + " needs " + pages + " frame(s), only "
                        + countFreeFrames() + " free.");
                liberateMemory(pages - countFreeFrames(), t);
            }
            if (countFreeFrames() >= pages) {
                j.frames.addAll(allocateFrames(j.label(), pages));
                j.state = JobState.RUNNING;
                j.loadTime = t;
                it.remove();
                System.out.println("  [t=" + t + "] " + j.label() + " loaded (" + j.sizeKB + " KB) into frames "
                        + j.frames + (t > j.arrivalTime ? " after waiting since t=" + j.arrivalTime : "") + ".");
            } else {
                System.out.println("  [t=" + t + "] " + j.label() + " waits for memory.");
            }
        }
    }

    /**
     * Tries to load every file that is requested at interval t or is still pending.
     * @param t current interval
     */
    public void loadFiles(int t) {
        for (FileRequest r : fileRequests) {
            boolean dueNow = r.state == FileRequestState.NOT_REQUESTED && r.requestTime == t;
            boolean retry = r.state == FileRequestState.PENDING;
            if (dueNow || retry) {
                if (retry) {
                    System.out.println("  [t=" + t + "] Retrying file '" + r.fileName + "'.");
                }
                allocateFileInMemory(r, t);
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Simulation driver                                                  */
    /* ------------------------------------------------------------------ */

    /**
     * Runs the simulation from interval 1 to endTime. The order of work in each interval is:
     * update job states, release expired files, load jobs, load files, swap jobs back in,
     * then print the memory map.
     * @param endTime last interval to simulate
     */
    public void runSimulation(int endTime) {
        System.out.println("Memory: " + memorySizeKB + " KB, page size " + pageSizeKB + " KB -> "
                + numberOfFrames + " frames.  Disk block size: " + disk.blockSizeKB + " KB.");
        printJobTable();
        System.out.println();
        for (int t = 1; t <= endTime; t++) {
            updateJobStates(t);
            releaseExpiredFiles(t);
            loadJobs(t);
            loadFiles(t);
            swapInJobs(t);
            printMemoryMap(t);
        }
        printSummary();
    }

    /**
     * @return a good end time: one interval after the last file must leave memory or the
     *         last job finishes, so the release of the file is visible.
     */
    public int suggestedEndTime() {
        int end = 1;
        for (Job j : jobs) {
            end = Math.max(end, j.arrivalTime + j.executionInterval);
        }
        for (FileRequest r : fileRequests) {
            end = Math.max(end, r.stayUntil + 1);
        }
        return end;
    }

    /* ------------------------------------------------------------------ */
    /*  Frame helpers                                                      */
    /* ------------------------------------------------------------------ */

    /** @return the number of free page frames in memory. */
    public int countFreeFrames() {
        int free = 0;
        for (String owner : frameTable) {
            if (owner == null) {
                free++;
            }
        }
        return free;
    }

    /**
     * Gives the first `count` free frames to an owner (paging: frames need not be contiguous).
     * The caller must check that enough frames are free.
     * @param owner label of the owner ("J1", "F1", ...)
     * @param count number of frames
     * @return the frame numbers allocated
     */
    private List<Integer> allocateFrames(String owner, int count) {
        List<Integer> allocated = new ArrayList<>();
        for (int f = 0; f < numberOfFrames && allocated.size() < count; f++) {
            if (frameTable[f] == null) {
                frameTable[f] = owner;
                allocated.add(f);
            }
        }
        return allocated;
    }

    /**
     * Marks a list of frames as free and clears the list.
     * @param frames frames to free
     */
    private void freeFrames(List<Integer> frames) {
        for (int f : frames) {
            frameTable[f] = null;
        }
        frames.clear();
    }

    /**
     * Integer division rounded up (e.g. 7 KB with 2 KB pages = 4 pages).
     * @param a dividend
     * @param b divisor (> 0)
     * @return ceil(a / b)
     */
    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }

    /* ------------------------------------------------------------------ */
    /*  Printing helpers                                                   */
    /* ------------------------------------------------------------------ */

    /** Prints the job table given as input. */
    private void printJobTable() {
        System.out.println("Job table:");
        System.out.println("  Job  Start  Size(KB)  Exec.interval  Runs during   Sleeps from");
        for (Job j : jobs) {
            System.out.printf("  %-4d %-6d %-9d %-14d %-13s %d%n", j.id, j.arrivalTime, j.sizeKB,
                    j.executionInterval, j.arrivalTime + ".." + (j.arrivalTime + j.executionInterval - 1),
                    j.arrivalTime + j.executionInterval);
        }
        System.out.println("File requests:");
        for (FileRequest r : fileRequests) {
            DirectoryEntry e = disk.directory.get(r.fileName);
            System.out.println("  " + r.label + " = '" + r.fileName + "' (" + (e == null ? "?" : e.sizeKB)
                    + " KB) load at t=" + r.requestTime + ", stay until t=" + r.stayUntil);
        }
    }

    /**
     * Prints the fragments of a file and the chain they form on the disk.
     * @param fragments the fragments of the file
     */
    private void printFragments(List<Fragment> fragments) {
        StringBuilder chain = new StringBuilder();
        int total = 0;
        for (Fragment f : fragments) {
            chain.append(f.blockNumber).append(" -> ");
            total += f.sizeKB;
        }
        chain.append("END");
        System.out.println("    Linked chain on disk: " + chain);
        System.out.println("    Fragments collected: " + fragments.size() + " (" + total + " KB of data on disk)");
        for (Fragment f : fragments) {
            System.out.println("      fragment " + f.order + ": disk block " + f.blockNumber + ", " + f.sizeKB + " KB");
        }
    }

    /**
     * Prints which memory frame holds each page of a file and which disk block the
     * page's data comes from.
     * @param r the loaded file request
     */
    private void printFilePageTable(FileRequest r) {
        for (int page = 0; page < r.frames.size(); page++) {
            int firstKB = page * pageSizeKB;                 // first KB of the file in this page
            int fragmentIndex = firstKB / disk.blockSizeKB;  // fragment that holds that KB
            String source;
            if (fragmentIndex < r.fragments.size() && r.fragments.get(fragmentIndex).sizeKB > firstKB % disk.blockSizeKB) {
                source = "disk block " + r.fragments.get(fragmentIndex).blockNumber;
            } else {
                source = "no data on disk (space reserved for declared size)";
            }
            System.out.println("      page " + page + " -> frame " + r.frames.get(page) + "   (" + source + ")");
        }
    }

    /**
     * Prints the memory map (one cell per frame) and the job/file states at interval t.
     * @param t current interval
     */
    private void printMemoryMap(int t) {
        StringBuilder map = new StringBuilder();
        for (String owner : frameTable) {
            map.append(String.format("%-3s", owner == null ? "." : owner));
        }
        System.out.printf("t=%-3d|%s| free=%d%n", t, map, countFreeFrames());
        StringBuilder states = new StringBuilder("       ");
        for (Job j : jobs) {
            if (j.state != JobState.NOT_ARRIVED) {
                states.append(j.label()).append(':').append(shortState(j.state)).append(' ');
            }
        }
        for (FileRequest r : fileRequests) {
            if (r.state != FileRequestState.NOT_REQUESTED) {
                states.append(r.label).append(':').append(r.state).append(' ');
            }
        }
        System.out.println(states.toString().stripTrailing());
    }

    /**
     * @param s a job state
     * @return a short name for the state map line
     */
    private static String shortState(JobState s) {
        switch (s) {
            case RUNNING:     return "run";
            case SLEEPING:    return "sleep";
            case SWAPPED_OUT: return "swapped";
            case WAITING:     return "wait";
            default:          return "-";
        }
    }

    /** Prints the final result of every file request. */
    private void printSummary() {
        System.out.println("Summary:");
        for (FileRequest r : fileRequests) {
            String result;
            switch (r.state) {
                case RELEASED:
                case IN_MEMORY:
                    result = "loaded at t=" + r.loadedAt + (r.loadedAt > r.requestTime ? " (DELAYED)" : "")
                            + ", " + r.requiredPages + " page(s), " + r.state;
                    break;
                default:
                    result = r.state.toString();
            }
            System.out.println("  " + r.label + " '" + r.fileName + "': " + result);
        }
    }

    /* =====================================================================================
     *  Test driver
     * ===================================================================================== */

    /** @return the job table of the assignment's use case (fresh objects every call). */
    static List<Job> assignmentJobs() {
        List<Job> jobs = new ArrayList<>();
        jobs.add(new Job(1, 1, 2, 7));
        jobs.add(new Job(2, 2, 3, 8));
        jobs.add(new Job(3, 3, 4, 6));
        jobs.add(new Job(4, 4, 3, 6));
        jobs.add(new Job(5, 5, 2, 9));
        jobs.add(new Job(6, 6, 3, 6));
        jobs.add(new Job(7, 7, 2, 6));
        return jobs;
    }

    /**
     * Prints a scenario banner.
     * @param title title of the scenario
     */
    static void banner(String title) {
        System.out.println();
        System.out.println("=".repeat(80));
        System.out.println(title);
        System.out.println("=".repeat(80));
    }

    /**
     * Entry point. With no arguments, runs the assignment use case and five other test
     * files. With "custom" arguments, runs one file of your choice against the job table.
     * @param args optional: custom fileSizeKB loadInterval stayUntil block1 block2 ...
     */
    public static void main(String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("custom")) {
            runCustom(args);
            return;
        }

        // ---------- Test 1: the assignment use case ----------
        banner("TEST 1 - Assignment use case: 8 KB file, blocks 28,5,12,13,1,4, load t=12, stay until t=16");
        HardDisk disk1 = new HardDisk(64, 1);
        disk1.storeFile("assignment_file.dat", 8, new int[]{28, 5, 12, 13, 1, 4});
        FileManagement fm1 = new FileManagement(20, 1, disk1, assignmentJobs());
        fm1.addFileRequest("assignment_file.dat", 12, 16);
        fm1.runSimulation(fm1.suggestedEndTime());

        // ---------- Test 2: small file, fits without swapping ----------
        banner("TEST 2 - Small 4 KB file at t=3 (memory still has room, no swapping needed)");
        HardDisk disk2 = new HardDisk(64, 1);
        disk2.storeFile("small.txt", 4, new int[]{7, 2, 9, 20});
        FileManagement fm2 = new FileManagement(20, 1, disk2, assignmentJobs());
        fm2.addFileRequest("small.txt", 3, 5);
        fm2.runSimulation(fm2.suggestedEndTime());

        // ---------- Test 3: disk blocks of 2 KB, file with a partly-filled last block ----------
        banner("TEST 3 - 2 KB disk blocks, 7 KB file (last block half full), load t=10 until t=13");
        HardDisk disk3 = new HardDisk(32, 2);
        disk3.storeFile("report.doc", 7, new int[]{3, 10, 6, 15});
        FileManagement fm3 = new FileManagement(20, 1, disk3, assignmentJobs());
        fm3.addFileRequest("report.doc", 10, 13);
        fm3.runSimulation(fm3.suggestedEndTime());

        // ---------- Test 4: two different files in the same run ----------
        banner("TEST 4 - Two files: 3 KB at t=9..11 and 5 KB at t=13..15");
        HardDisk disk4 = new HardDisk(64, 1);
        disk4.storeFile("notes.txt", 3, new int[]{40, 41, 2});
        disk4.storeFile("image.bmp", 5, new int[]{30, 31, 32, 33, 34});
        FileManagement fm4 = new FileManagement(20, 1, disk4, assignmentJobs());
        fm4.addFileRequest("notes.txt", 9, 11);
        fm4.addFileRequest("image.bmp", 13, 15);
        fm4.runSimulation(fm4.suggestedEndTime());

        // ---------- Test 5: file bigger than memory + file needing more than sleepers hold ----------
        banner("TEST 5 - Error cases: 25 KB file (> memory) and 18 KB file at t=8 (not enough sleepers)");
        HardDisk disk5 = new HardDisk(64, 1);
        int[] bigChain = new int[25];
        for (int i = 0; i < 25; i++) {
            bigChain[i] = 63 - i;
        }
        disk5.storeFile("huge.iso", 25, bigChain);
        int[] chain18 = new int[18];
        for (int i = 0; i < 18; i++) {
            chain18[i] = i;
        }
        disk5.storeFile("video.mp4", 18, chain18);
        FileManagement fm5 = new FileManagement(20, 1, disk5, assignmentJobs());
        fm5.addFileRequest("huge.iso", 12, 16);
        fm5.addFileRequest("video.mp4", 8, 15);
        fm5.runSimulation(fm5.suggestedEndTime());

        // ---------- Test 6: corrupted linked chain (loop) ----------
        banner("TEST 6 - Corrupted chain: block 13 points back to block 5 (loop is detected)");
        HardDisk disk6 = new HardDisk(64, 1);
        disk6.storeFile("broken.dat", 6, new int[]{28, 5, 12, 13, 1, 4});
        disk6.blocks[13].next = 5;   // simulate disk corruption
        FileManagement fm6 = new FileManagement(20, 1, disk6, assignmentJobs());
        fm6.addFileRequest("broken.dat", 12, 16);
        fm6.runSimulation(13);
    }

    /**
     * Runs one user-defined file against the assignment's job table
     * (20 KB memory, 1 KB pages, 1 KB disk blocks).
     * @param args "custom" fileSizeKB loadInterval stayUntil block1 block2 ...
     */
    static void runCustom(String[] args) {
        if (args.length < 5) {
            System.out.println("Usage: java FileManagement custom <fileSizeKB> <loadInterval> <stayUntil> <block1> [block2 ...]");
            return;
        }
        try {
            int sizeKB = Integer.parseInt(args[1]);
            int load = Integer.parseInt(args[2]);
            int until = Integer.parseInt(args[3]);
            int[] chain = new int[args.length - 4];
            for (int i = 4; i < args.length; i++) {
                chain[i - 4] = Integer.parseInt(args[i]);
            }
            banner("CUSTOM - " + sizeKB + " KB file, load t=" + load + ", stay until t=" + until);
            HardDisk disk = new HardDisk(128, 1);
            disk.storeFile("custom_file", sizeKB, chain);
            FileManagement fm = new FileManagement(20, 1, disk, assignmentJobs());
            fm.addFileRequest("custom_file", load, until);
            fm.runSimulation(fm.suggestedEndTime());
        } catch (IllegalArgumentException e) {
            System.out.println("Invalid input: " + e.getMessage());
        }
    }
}