# BSBM -- v0.2, modified

Changes:

* Adds support for "jena:" endpoint - a local Apache Jena Assembler file.
* Additional presentation of results (simple plain tables).
* Tidy code, create clean POM build.
* Reorganise scripts into separate areas.
* Record queries made 

The orginal code for comparision is in `bsbmtools-0.2` (a subset of the
v0.2 distribution).

## BSBM-Local

This is the Berlin SPARQL Benchmark with additions for testing local
Apache Jena stores.  BSBM runs all the queries over the SPARQL Protocol
whereas can also the stores directly, so removing protocol overhead.
For the smaller datasets and simpler queries, the overheads can be a
significant part of the cost so evaluation of stores can be skewed.

BSBM-Local adds new endpoint formats as pseudo-URI schemes:

* `jena:<assembler>` where `assembler` is the file name of a Jena assembler
description for the store.

### Get the system and setup

```
git clone git://github.com/afs/BSBM-Local.git
cd Code
mvn clean package
```

Check classpath in `/bin/bsbm.classpath`.

_Note: `GenData` and `Run` need to have a symbolic link "Data" to
the data area._

### Generate data

The original BSBM datasets are available from:

http://wifo5-03.informatik.uni-mannheim.de/bizer/berlinsparqlbenchmark/datasets/

`/GenData/generate` generates new datasets. See the scrpt for details.

### Run the benchmark

The `/Run` area has a "run" script.

There is a new argument, "-gen", that instead of running the benchmark,
writes the queries out into the file `run-details.json`. This can be
used to see exeactly what the benchamr would execute and eaily extract
any interesting queries.

Argument "-r" has been added as a synonym for "-runs".

### Geneate HTML results.

In the  `/Run` area, the script "results" generates

    results Results/res-??.xml ...

where `??` are any size names. e.g. `res-1m.xml`. An HTML file is written to stdout.

The original results generator can be run with "results_bm" and it
writes generated HTML files in `HTML/`.

### Processes for TDB backed stores.

Choose sizes wanted - edit this script.

`generate`

Run the tdbloader, create stats files and assembler files (again, edit
the script for sizes to setup).

`buildDB`

### Questions?

If about BSBM generaly, ask the authors, see 
http://wifo5-03.informatik.uni-mannheim.de/bizer/berlinsparqlbenchmark/

If about the local customizations, please send questions to:

    users@jena.apache.org

(subscribe to the list before sending)
