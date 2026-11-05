# OS-file-managment-project
Computer Science 305: Operating Systems project 2
# OS File Management Emulator: Loading a Linked-Allocation File into Memory

This Java program emulates how an operating system brings a file from the hard disk into main memory.

- Files are stored on a simulated disk with **linked storage allocation**. The directory keeps only the first block, and each block points to the next one (`-1` = end of file).
- Main memory is **paged**. The use case has 20 KB of memory and 1 KB pages, so 20 frames.
- If memory is full, **sleeping jobs are swapped out** to make room. This is the memory-management technique from Assignment 1.

All the file-related work is done by methods of the `FileManagement` class.

| Requirement | Method |
|---|---|
| Collect all fragments of the file from the disk | `collectFileFragments(fileName)` follows the block chain and detects broken links and loops |
| Calculate the memory space the file needs (file size only) | `calculateRequiredMemory(fileName, fragments)` |
| Allocate memory, freeing space if needed | `allocateFileInMemory(request, t)` and `liberateMemory(framesNeeded, t)` |
| Keep the file until its last interval, then release it | `releaseExpiredFiles(t)` and `swapInJobs(t)` |

## How to run

You need Java 11 or newer.

```bash
cd src
javac FileManagement.java
java FileManagement
```

Running `java FileManagement` runs the assignment use case and five other test files.

To test any other file against the same job table:

```bash
java FileManagement custom <fileSizeKB> <loadInterval> <stayUntil> <block1> <block2> ...
# example: the assignment file
java FileManagement custom 8 12 16 28 5 12 13 1 4
```

The full output of a run is in [`sample_output.txt`](sample_output.txt).

## Assumptions

1. **Time.** A job loaded at interval `L` with execution interval `E` runs during `L … L+E-1`. From `L+E` on it sleeps and keeps its memory. For example, Job 1 runs 1–7 and sleeps from 8.
2. **The file's stay period.** The file is loaded at its load interval and stays in memory through its "stay until" interval. Its frames are freed at the start of the next interval (17 in the use case).
3. **Freeing memory.** Only SLEEPING jobs can be swapped out. The longest-sleeping job goes first, and ties go to the lower job ID. RUNNING jobs are never removed. If the sleeping jobs together cannot free enough frames, no job is swapped out and the file waits. It is retried every interval until its stay period ends.
4. **Frames.** With paging, frames do not need to be next to each other. The first free frames are used.
5. **Swapped-out jobs come back** once there is free room and nothing else is waiting for memory. A job never comes back in the same interval it was swapped out.
6. **The use case data does not add up.** The file is 8 KB with 1 KB blocks, which would need 8 blocks, but only 6 blocks are listed (28, 5, 12, 13, 1, 4). The program walks the chain, finds 6 KB of data, and prints a warning. It then reserves the **larger** size (8 KB = 8 pages) so the whole file always fits. If you run the custom mode with 6 KB, only Jobs 1 and 3 are swapped out.

## Result for the assignment use case

At interval 12, Jobs 1–7 hold 19 of the 20 frames. Jobs 5 and 7 are running; Jobs 1, 2, 3, 4 and 6 are sleeping. So 1 frame is free and 8 are needed.

| Step | Result |
|---|---|
| Chain on disk | 28 → 5 → 12 → 13 → 1 → 4 → END (6 fragments) |
| Required memory | 8 pages (8 KB) |
| Swapped out | J1 (2 frames), J3 (4 frames), J2 (3 frames): 10 frames freed |
| File page table | pages 0–7 → frames 0–7 |
| Interval 17 | File released, and J2 and J3 are swapped back in |

## Other test files (in `main`)

| Test | What it checks |
|---|---|
| 2 | A small 4 KB file at t=3 that fits without swapping |
| 3 | 2 KB disk blocks and a 7 KB file whose last block is half full (block size ≠ page size) |
| 4 | Two different files loaded at different times in the same run |
| 5 | A 25 KB file bigger than memory (rejected), and an 18 KB file that waits until enough jobs are asleep |
| 6 | A corrupted chain with a loop, which is detected and the file rejected |

## Files

```
src/FileManagement.java      source code (all classes, fully commented)
sample_output.txt            output of `java FileManagement`
submission/FileManagement.txt  same code as a .txt file for submission
```