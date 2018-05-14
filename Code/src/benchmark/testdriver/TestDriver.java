/*
 * Copyright (C) 2008 Andreas Schultz
 *
 *   This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   any later version.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package benchmark.testdriver;

import java.io.* ;
import java.nio.file.Files;
import java.util.* ;

import benchmark.qualification.QueryResult ;
import org.apache.jena.Jena;
import org.apache.jena.atlas.io.IO;
import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonArray;
import org.apache.jena.atlas.json.JsonBuilder;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.atlas.lib.DateTimeUtils;
import org.apache.jena.atlas.lib.FileOps ;
import org.apache.jena.system.JenaSystem;
import org.apache.log4j.Level ;
import org.apache.log4j.Logger ;
import org.apache.log4j.PropertyConfigurator ;
import org.apache.log4j.xml.DOMConfigurator ;

public class TestDriver {
	protected QueryMix queryMix;// The Benchmark Querymix
    // how many Query mixes are run for warm up
	protected int warmups = TestDriverDefaultValues.warmups;

	// Where to get the query parameters from
	protected AbstractParameterPool parameterPool;
    // only important for single threaded runs
	protected ServerConnection server;
	// where to take the queries from
	protected File usecaseFile = TestDriverDefaultValues.usecaseFile;
	protected int nrRuns = TestDriverDefaultValues.nrRuns;

	// For the random number generators
	protected long seed = TestDriverDefaultValues.seed;
	protected String sparqlEndpoint = null;
	protected String sparqlUpdateEndpoint = null;
	protected static String sparqlUpdateQueryParameter = TestDriverDefaultValues.updateQueryParameter;
	protected String defaultGraph = TestDriverDefaultValues.defaultGraph;
    // Where to take the Test Driver data from
	protected String resourceDir = TestDriverDefaultValues.resourceDir;
	protected String xmlResultFile = TestDriverDefaultValues.xmlResultFile;
	protected static Logger logger = Logger.getLogger(TestDriver.class);
	protected String updateFile = null;
	protected boolean[] ignoreQueries;// Queries to ignore
	protected boolean doSQL = false;
	protected boolean multithreading = false;
	protected int nrThreads;
	protected int timeout = TestDriverDefaultValues.timeoutInMs;
	protected String driverClassName = TestDriverDefaultValues.driverClassName;
	protected boolean qualification = TestDriverDefaultValues.qualification;
	protected String qualificationFile = TestDriverDefaultValues.qualificationFile;
	// Generate queries, do not run them.
	protected boolean generate = false;

	/*
	 * Parameters for steady state
	 */
	// Querymixes per measuring period
	protected int qmsPerPeriod = TestDriverDefaultValues.qmsPerPeriod;
	// Difference in percent between min and max measurement period
	protected double percentDifference = TestDriverDefaultValues.percentDifference;
	// The last nrOfPeriods periods are compared
	protected int nrOfPeriods = TestDriverDefaultValues.nrOfPeriods;
	protected boolean rampup = false;

	public TestDriver(String[] args) {
		processProgramParameters(args);
		System.out.print("Reading Test Driver data...");
		System.out.flush();
		if (doSQL)
			parameterPool = new SQLParameterPool(new File(resourceDir), seed);
		else {
			if (updateFile == null)
				parameterPool = new LocalSPARQLParameterPool(new File(
						resourceDir), seed);
			else
				parameterPool = new LocalSPARQLParameterPool(new File(
						resourceDir), seed, new File(updateFile));
		}
		System.out.println("done");
		
		if (sparqlEndpoint != null && !multithreading) {
		    if (doSQL)
		        server = new SQLConnection(sparqlEndpoint, timeout,
		                                   driverClassName);
		    else {
		        if ( sparqlEndpoint.startsWith("jena:") )
		            server = new LocalConnectionJena(sparqlEndpoint, sparqlUpdateEndpoint, defaultGraph, timeout);
		        else if ( sparqlEndpoint.startsWith("sesame:") )
		            //server = new LocalConnectionSesame(sparqlEndpoint, defaultGraph, timeout);
		            throw new UnsupportedOperationException("sesame: URLs not supported") ;
		        else
		            server = new SPARQLConnection(sparqlEndpoint,
		                                          sparqlUpdateEndpoint, defaultGraph, timeout);
		    }
		} else if (multithreading) {
			// do nothing
		} else {
			printUsageInfos();
			System.exit(-1);
		}

		TestDriverShutdown tds = new TestDriverShutdown(this);
		Runtime.getRuntime().addShutdownHook(tds);
	}

	/*
	 * Read which query mixes (directories) are used in the use case
	 */
	private List<String> getUseCaseQuerymixes() {
		List<String> files = new ArrayList<String>();

		try ( BufferedReader reader = new BufferedReader(new FileReader( usecaseFile)) ) {
			String line = null;
			while ((line = reader.readLine()) != null) {
				String[] querymixInfo = line.split("=");
				if (querymixInfo.length != 2) {
					System.err.println("Invalid entry in use case file "
							+ usecaseFile + ":\n");
					System.err.println(line);
					System.exit(-1);
				}
				if (querymixInfo[0].toLowerCase().equals("querymix"))
					files.add(querymixInfo[1]);
			}
		} catch (IOException e) {
			System.err.println(e.getMessage());
			System.exit(-1);
		}
		return files;
	}

	/*
	 * Get the querymix ordering information
	 */
	private List<Integer[]> getQuerymixRuns(List<String> querymixDirs) {
		List<Integer[]> runs = new ArrayList<Integer[]>();
		for (String querymixDir : querymixDirs) {
			runs.add(getQueryMixInfo(new File(querymixDir, "querymix.txt")));
		}
		return runs;
	}

	private List<Integer> getMaxQueryNrs(List<Integer[]> querymixRuns) {
		List<Integer> maxNrs = new ArrayList<Integer>();
		for (Integer[] run : querymixRuns) {
			Integer maxQueryNr = 0;
			for (int i = 0; i < run.length; i++) {
				if (run[i] != null && run[i] > maxQueryNr)
					maxQueryNr = run[i];
			}
			maxNrs.add(maxQueryNr);
		}
		return maxNrs;
	}

	private List<boolean[]> getIgnoredQueries(List<String> querymixDirs,
			List<Integer> maxQueryNrs) {
		List<boolean[]> ignoredQueries = new ArrayList<boolean[]>();
		Iterator<String> queryMixDirIterator = querymixDirs.iterator();
		Iterator<Integer> maxQueryNrIterator = maxQueryNrs.iterator();
		while (queryMixDirIterator.hasNext()) {
			File ignoreFile = new File(queryMixDirIterator.next(),
					"ignoreQueries.txt");
			int maxQueryNr = maxQueryNrIterator.next();

			boolean[] ignoreQueries = new boolean[maxQueryNr];
			if (!ignoreFile.exists()) {
				for (int i = 0; i < ignoreQueries.length; i++) {
					ignoreQueries[i] = false;
				}
			} else
				ignoreQueries = getIgnoreQueryInfo(ignoreFile, maxQueryNr);

			ignoredQueries.add(ignoreQueries);
		}
		return ignoredQueries;
	}

	private List<Query[]> getQueries(List<String> querymixDirs,
			List<Integer[]> queryRuns, List<Integer> maxQueryNrs) {
		List<Query[]> allQueries = new ArrayList<Query[]>();

		Iterator<String> queryMixDirIterator = querymixDirs.iterator();
		Iterator<Integer[]> queryRunIterator = queryRuns.iterator();
		Iterator<Integer> maxQueryNrIterator = maxQueryNrs.iterator();

		while (queryMixDirIterator.hasNext()) {
			Integer[] queryRun = queryRunIterator.next();
			String queryDir = queryMixDirIterator.next();
			Query[] queries = new Query[maxQueryNrIterator.next()];

			for (int i = 0; i < queryRun.length; i++) {
				if (queryRun[i] != null) {
					Integer qnr = queryRun[i];
					if (queries[qnr - 1] == null) {
						File queryFile = new File(queryDir, "query"+qnr+".txt");
						File queryDescFile = new File(queryDir, "query"+qnr+"desc.txt");
						if (doSQL)
							queries[qnr - 1] = new Query(queryFile, qnr, queryDescFile, "@");
						else
							queries[qnr - 1] = new Query(queryFile, qnr, queryDescFile, "%");

						// Read qualification information
						if (qualification) {
							File queryValidFile = new File(queryDir, "query"+qnr+"valid.txt");
							String[] rowNames = getRowNames(queryValidFile);
							queries[qnr - 1].setRowNames(rowNames);
						}
					}
				}
			}
			allQueries.add(queries);
		}
		return allQueries;
	}

	/*
	 * Read in query mixes / queries
	 */
	public void init() {
		Query[] queries = null;
		Integer[] queryRun = null;

		List<String> querymixDirs = getUseCaseQuerymixes();

		List<Integer[]> queryRuns = getQuerymixRuns(querymixDirs);

		List<Integer> maxQueryPerRun = getMaxQueryNrs(queryRuns);

		List<boolean[]> ignoreQueries = getIgnoredQueries(querymixDirs,
				maxQueryPerRun);

		List<Query[]> queriesOfQuerymixes = getQueries(querymixDirs, queryRuns,
				maxQueryPerRun);

		queries = setupQueries(maxQueryPerRun, queriesOfQuerymixes);

		this.ignoreQueries = setupIgnoreQueries(maxQueryPerRun, ignoreQueries);

		queryRun = setupQueryRun(maxQueryPerRun, queryRuns);

		queryMix = new QueryMix(queries, queryRun);
	}

	/*
	 * Combine the query sequences of different query mixes
	 */
	private Integer[] setupQueryRun(List<Integer> maxQueryPerRun,
			List<Integer[]> queryRuns) {
		Integer[] queryRun;
		int nrOfQueriesInRun = 0;

		for (Integer[] qr : queryRuns)
			nrOfQueriesInRun += qr.length;
		queryRun = new Integer[nrOfQueriesInRun];

		int indexOffset = 0;
		int queryOffset = 0;

		Iterator<Integer> maxQueryNrIterator = maxQueryPerRun.iterator();
		Iterator<Integer[]> queryRunIterator = queryRuns.iterator();
		while (queryRunIterator.hasNext()) {
			Integer[] qr = queryRunIterator.next();
			int queryIndex = 0;
			for (Integer queryNr : qr) {
				queryRun[indexOffset + queryIndex] = queryNr + queryOffset;
				queryIndex++;
			}
			indexOffset += qr.length;
			queryOffset += maxQueryNrIterator.next();
		}
		return queryRun;
	}

	/*
	 * Combine ignored queries of different query mixes
	 */
	private boolean[] setupIgnoreQueries(List<Integer> maxQueryPerRun,
			List<boolean[]> ignoreQueriesOfQuerymixes) {
		boolean[] ignoreQueries;
		int nrOfQueries = 0;

		for (int i : maxQueryPerRun)
			nrOfQueries += i;
		ignoreQueries = new boolean[nrOfQueries];

		int queryOffset = 0;

		Iterator<Integer> maxQueryNrIterator = maxQueryPerRun.iterator();
		Iterator<boolean[]> ignoreQueriesIterator = ignoreQueriesOfQuerymixes
				.iterator();
		while (ignoreQueriesIterator.hasNext()) {
			boolean[] iqs = ignoreQueriesIterator.next();
			int queryIndex = 0;
			for (boolean igQuery : iqs) {
				ignoreQueries[queryOffset + queryIndex] = igQuery;
				queryIndex++;
			}

			queryOffset += maxQueryNrIterator.next();
		}
		return ignoreQueries;
	}

	private Query[] setupQueries(List<Integer> maxQueryPerRun,
			List<Query[]> queriesOfQuerymixes) {
		Query[] queries;
		int nrOfQueries = 0;

		for (int i : maxQueryPerRun)
			nrOfQueries += i;
		queries = new Query[nrOfQueries];

		for (int i = 0; i < queries.length; i++) {
			queries[i] = null;
		}

		int queryOffset = 0;

		Iterator<Integer> maxQueryNrIterator = maxQueryPerRun.iterator();
		Iterator<Query[]> queriesIterator = queriesOfQuerymixes.iterator();
		while (queriesIterator.hasNext()) {
			Query[] qs = queriesIterator.next();
			int queryIndex = 0;
			for (Query query : qs) {
				queries[queryOffset + queryIndex] = query;
				queryIndex++;
			}

			queryOffset += maxQueryNrIterator.next();
		}
		return queries;
	}

	private String[] getRowNames(File file) {
		ArrayList<String> rowNames = new ArrayList<String>();

		try {
		    String data = L.readWholeFile(file);
			StringTokenizer st = new StringTokenizer(data);
			while (st.hasMoreTokens()) {
				rowNames.add(st.nextToken());
			}
		} catch (IOException e) {
			System.err
					.println("Error processing query qualification-info file: "
							+ file.getAbsolutePath());
			System.exit(-1);
		}
		return rowNames.toArray(new String[1]);
	}

	private Integer[] getQueryMixInfo(File file) {
		System.out.println("Reading query mix file: " + file);
		ArrayList<Integer> qm = new ArrayList<Integer>();

		try {
		    String data = L.readWholeFile(file);
			StringTokenizer st = new StringTokenizer(data);
			while (st.hasMoreTokens()) {
				qm.add(Integer.parseInt(st.nextToken()));
			}
		} catch (IOException e) {
			System.err.println("Error processing query mix file: " + file);
			System.exit(-1);
		}
		return qm.toArray(new Integer[1]);
	}

	private boolean[] getIgnoreQueryInfo(File file, int maxQueryNumber) {
		System.out.println("Reading query ignore file: " + file);
		boolean[] ignoreQueries = new boolean[maxQueryNumber];

		for (int i = 0; i < maxQueryNumber; i++)
			ignoreQueries[i] = false;

		try {
		    String data = L.readWholeFile(file);
			StringTokenizer st = new StringTokenizer(data);
			while (st.hasMoreTokens()) {
				Integer queryNr = Integer.parseInt(st.nextToken());
				if (queryNr > 0 && queryNr <= maxQueryNumber)
					ignoreQueries[queryNr - 1] = true;
			}
		} catch (IOException e) {
			System.err.println("Error processing query ignore file: " + file);
			System.exit(-1);
		}
		return ignoreQueries;
	}

	public void run() {
		int qmsPerPeriod = TestDriverDefaultValues.qmsPerPeriod;
		int qmsCounter = 0;
		int periodCounter = 0;
		double periodRuntime = 0;
		BufferedWriter measurementFile = null;
		try {
			measurementFile = new BufferedWriter(new FileWriter("steadystate.tsv"));
		} catch (IOException e) {
			System.err.println("Could not create file steadystate.tsv!");
			System.exit(-1);
		}

		JsonObject outer = new JsonObject();
		
		// Parameter choices.
		
		outer.put("generated", DateTimeUtils.nowAsString());
		String dataset = resourceDir.replaceFirst("[^-]*", "BSBM");
		outer.put("data_name", dataset);
		outer.put("warmups", warmups);
		outer.put("runs", nrRuns);

		JsonArray array = new JsonArray();
		int queryNumber = 0 ;
		
		for (int nrRun = -warmups; nrRun < nrRuns; nrRun++) {
			long startTime = System.currentTimeMillis();
			queryMix.setRun(nrRun);
			int queryInRun = 0 ;
			while (queryMix.hasNext()) {
				Query next = queryMix.getNext();
				// Don't run update queries on warm-up
				if (nrRun < 0 && next.getQueryType() == Query.UPDATE_TYPE) {
					queryMix.setCurrent(0, -1.0);
					continue;
				}

				Object[] queryParameters = parameterPool.getParametersForQuery(next);
				next.setParameters(queryParameters);
				
				if (ignoreQueries[next.getNr() - 1])
					queryMix.setCurrent(0, -1.0);
				else {
	                queryInRun++;
	                queryNumber++;
				    // INFO
				    //String phase = (nrRun>=0)?"run":"warmup";
				    QueryDetails details = next.details();
				    long queryInRun$ = queryInRun;
                    long queryNumber$ = queryNumber;
                    // Negative, warmup; no zero.
                    long nrRun$ = nrRun>=0 ? nrRun+1 : nrRun;
				    
				    JsonObject obj = JsonBuilder.buildObject((b)->{
                        b.pair("query_number", queryNumber$)
                         .pair("run_loop", nrRun$)
                         .pair("query_in_loop", queryInRun$)
				         .pair("group", next.getQueryGroup())
                         //.pair("phase", phase)
				         .pair("query", next.getQueryString());

                        b.pair("template", details.queryTemplate);
				        b.key("params");
				        b.startArray();
				        for ( Param param : details.params) {
				            b.startObject();
				            b.pair("name", param.name);
				            b.pair("value", param.value);
				            b.finishObject();
				        }
				        b.finishArray();
				    });
				    array.add(obj);
                    // INFO
				    
				    if ( generate ) {
				        //System.out.println(nrRun+":"+queryInRun+" :: q="+details.queryNumber);
				        //System.out.println("Generate: "+nrRun+":"+queryInRun);
				        queryMix.setCurrent(0, -1.0);
				    }
				    else
				        server.executeQuery(next, next.getQueryType());
				}
			}
			

			// Ignore warm-up measures
			if (nrRun >= 0) {
				qmsCounter++;
				periodRuntime += queryMix.getQueryMixRuntime();
			}

			// Write out period data
			if (qmsCounter == qmsPerPeriod) {
				periodCounter++;
				try {
					measurementFile.append(periodCounter + "\t" + periodRuntime
							+ "\n");
					measurementFile.flush();
				} catch (IOException e) {
					e.printStackTrace();
					System.exit(-1);
				}
				periodRuntime = 0;
				qmsCounter = 0;
			}

			if ( generate ) {
			    System.out.println("Generate: run="+nrRun);
			} else {
    			System.out.println(nrRun
    					+ ": "
    					+ String.format(Locale.ROOT, "%.2f", queryMix
    							.getQueryMixRuntime() * 1000) + "ms, total: "
    					+ (System.currentTimeMillis() - startTime) + "ms");
			}
			queryMix.finishRun();
		}
		// Output details.
        outer.put("data", array);
        try ( OutputStream outStream = new FileOutputStream("run-details.json")) {
            JSON.write(outStream, outer);
        } catch (IOException e1) { IO.exception(e1); }

        if ( generate )
            logger.log(Level.ALL, "Generate: w="+warmups+",r="+nrRuns);
        else
            logger.log(Level.ALL, printResults(true));
        

		try (FileWriter resultWriter = new FileWriter(xmlResultFile)) {
			resultWriter.append(printXMLResults(true));
			resultWriter.flush();
			measurementFile.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public void runQualification() {
		File file = new File(qualificationFile);
		System.out.println("Creating qualification file: " + file.getAbsolutePath() + "\n");
		
		if ( Files.exists(file.toPath()) ) {
		    System.err.println("\nQualification file alreadty exists! "+file);
		    System.exit(1);
		}

		try(ObjectOutputStream objectOutput = new ObjectOutputStream(new FileOutputStream(file, false)) ) {
		    objectOutput.writeInt(queryMix.getQueries().length);
		    objectOutput.writeLong(seed);
		    objectOutput.writeInt(parameterPool.getScalefactor());
		    objectOutput.writeInt(nrRuns);
		    objectOutput.writeObject(queryMix.getQueryMix());
		    objectOutput.writeObject(ignoreQueries);

		    for (int nrRun = 0; nrRun < nrRuns; nrRun++) {
		        queryMix.setRun(nrRun + 1);
		        System.out.print("Run: " + (nrRun + 1));
		        while (queryMix.hasNext()) {
		            Query next = queryMix.getNext();
		            Object[] queryParameters = parameterPool
		                .getParametersForQuery(next);
		            next.setParameters(queryParameters);
		            if (ignoreQueries[next.getNr() - 1])
		                queryMix.setCurrent(0, -1.0);
		            else {
		                QueryResult queryResult = server.executeValidation(
		                    next, next.getQueryType());
		                if (queryResult != null)
		                    objectOutput.writeObject(queryResult);
		                queryMix.setCurrent(0, -1.0);
		            }
		            System.out.print(".");
		        }
		        queryMix.finishRun();
		        System.out.println("done");
		    }
		    objectOutput.flush();
		} catch (IOException e) {
		    e.printStackTrace();
		    System.exit(-1);
		}
		System.out.println("\nQualification file created!");

	}

	/*
	 * Ramp-up: Runs at least nrOfPeriods periods and checks after every period
	 * if the last nrOfPeriods periods differ at most percentDifference from
	 * each other. There is also a check if one of the examined periods is the
	 * minimum overall period, then the percentage check will be ignored.
	 */
	public void runRampup() {
		System.out
				.println("Starting Ramp-up. Writing measurement data to rampup.tsv");
		BufferedWriter measurementFile = null;
		try {
			measurementFile = new BufferedWriter(new FileWriter("rampup.tsv"));
		} catch (IOException e) {
			System.err.println("Could not create file rampup.tsv!");
			System.exit(-1);
		}

		int periodNr = 0;
		LinkedList<Double> periods = new LinkedList<Double>();
		double totalRuntime = 0;
		boolean unsteady = true;// Set to false after reaching steady state
		double minimumPeriod = Double.MAX_VALUE;
		while (unsteady) {

			periodNr++;
			double runtime = 0;
			// Running one period
			for (int nrRun = 1; nrRun <= qmsPerPeriod; nrRun++) {
				queryMix.setRun(nrRun);
				while (queryMix.hasNext()) {
					Query next = queryMix.getNext();
					// Don't execute Update queries in ramp-up
					if (next.getQueryType() == Query.UPDATE_TYPE) {
						queryMix.setCurrent(0, -1.0);
						continue;
					}
					Object[] queryParameters = parameterPool
							.getParametersForQuery(next);
					next.setParameters(queryParameters);
					if (ignoreQueries[next.getNr() - 1])
						queryMix.setCurrent(0, -1.0);
					else {
						server.executeQuery(next, next.getQueryType());
					}
				}
				runtime += queryMix.getQueryMixRuntime();
				System.out.println("Period "
						+ periodNr
						+ " Run: "
						+ nrRun
						+ ": "
						+ String.format(Locale.US, "%.3f", queryMix
								.getQueryMixRuntime() * 1000) + "ms");
				queryMix.finishRun();
			}

			// Write period/runtime pairs into rampup.tsv
			try {
				measurementFile.append(periodNr + "\t" + runtime + "\n");
				measurementFile.flush();
			} catch (IOException e) {
				e.printStackTrace();
				System.exit(-1);
			}
			totalRuntime += runtime;

			if (periodNr <= nrOfPeriods) {
				periods.addLast(runtime);
			} else {
				periods.addLast(runtime);
				periods.removeFirst();
				if (periods.size() != nrOfPeriods)
					throw new AssertionError();

				// Calculate difference between the periods
				double min = Double.MAX_VALUE;
				double max = Double.MIN_VALUE;
				for (double pVal : periods) {
					if (pVal < min)
						min = pVal;
					if (pVal > max)
						max = pVal;
					if (pVal <= minimumPeriod) {
						minimumPeriod = pVal;
						max = Double.MAX_VALUE;
						break;// Special case: If this period is lower than the
								// previous minimum period, go on.
					}
				}
				if ((max - min) / min < percentDifference)
					unsteady = false;
			}

			double all5 = 0;
			for (double pVal : periods) {
				all5 += pVal;
			}
			System.out.println("Total execution time for period " + periodNr
					+ "/last "
					+ (periodNr < nrOfPeriods ? periodNr : nrOfPeriods)
					+ " periods: "
					+ String.format(Locale.US, "%.3f", runtime * 1000) + "ms/"
					+ String.format(Locale.US, "%.3f", all5 * 1000) + "ms\n");
		}
		try {
			measurementFile.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
		System.out.println("Steady state reached after " + periodNr
				+ " measurement periods/"
				+ String.format(Locale.US, "%.3f", totalRuntime) + "s");
		server.close();
	}

	/*
	 * run the test driver in multi-threaded mode
	 */
	public void runMT() {
		ClientManager manager = new ClientManager(parameterPool, this);

		manager.createClients();
		manager.startWarmup();
		manager.startRun();
		logger.log(Level.ALL, printResults(true));
		try ( FileWriter resultWriter = new FileWriter(xmlResultFile) ) {
		    resultWriter.append(printXMLResults(true));
		    resultWriter.flush();
		    resultWriter.close();
		} catch (IOException e) {
		    e.printStackTrace();
		}
	}

	/*
	 * Process the program parameters typed on the command line.
	 */
	protected void processProgramParameters(String[] args) {
		int i = 0;
		while (i < args.length) {
			try {
				if (args[i].equals("-runs") || args[i].equals("-r")) {
					nrRuns = Integer.parseInt(args[i++ + 1]);
				} else if (args[i].equals("-idir")) {
					resourceDir = args[i++ + 1];
				} else if (args[i].equals("-warm") || args[i].equals("-w")) {
					warmups = Integer.parseInt(args[i++ + 1]);
				} else if (args[i].equals("-o")) {
					xmlResultFile = args[i++ + 1];
				} else if (args[i].equals("-dg")) {
					defaultGraph = args[i++ + 1];
				} else if (args[i].equals("-sql")) {
					doSQL = true;
				} else if (args[i].equals("-mt")) {
					if (rampup)
						throw new Exception(
								"Incompatible options: -mt and -rampup");
					multithreading = true;
					nrThreads = Integer.parseInt(args[i++ + 1]);
				} else if (args[i].equals("-seed")) {
					seed = Long.parseLong(args[i++ + 1]);
				} else if (args[i].equals("-t")) {
					timeout = Integer.parseInt(args[i++ + 1]);
				} else if (args[i].equals("-dbdriver")) {
					driverClassName = args[i++ + 1];
				} else if (args[i].equals("-qf")) {
					qualificationFile = args[i++ + 1];
				} else if (args[i].equals("-q")) {
					qualification = true;
					nrRuns = 15;
				} else if (args[i].equals("-rampup")) {
					if (multithreading)
						throw new Exception(
								"Incompatible options: -mt and -rampup");
					rampup = true;
				} else if (args[i].equals("-u")) {
					sparqlUpdateEndpoint = args[i++ + 1];
				} else if (args[i].equals("-udataset")) {
					updateFile = args[i++ + 1];
				} else if (args[i].equals("-ucf")) {
					usecaseFile = new File(args[i++ + 1]);
				} else if (args[i].equals("-uqp")) {
					sparqlUpdateQueryParameter = args[i++ + 1];
				} else if ( args[i].equals("-gen") ) {
				    generate = true;
				} else if (!args[i].startsWith("-")) {
					sparqlEndpoint = args[i];
				} else {
					if (!args[i].equals("-help"))
						System.err.println("Unknown parameter: " + args[i]);
					printUsageInfos();
					System.exit(-1);
				}

				i++;

			} catch (Exception e) {
				System.err.println("Invalid arguments:\n");
				e.printStackTrace();
				printUsageInfos();
				System.exit(-1);
			}
		}
	}

	/*
	 * Get Result String
	 */
	public String printResults(boolean all) {
		StringBuffer sb = new StringBuffer(100);
		double singleMultiRatio = 0.0;

		sb.append("Scale factor:           " + parameterPool.getScalefactor()
				+ "\n");
		sb.append("Number of warmup runs:  " + warmups + "\n");
		if (multithreading)
			sb.append("Number of clients:      " + nrThreads + "\n");
		sb.append("Seed:                   " + seed + "\n");
		sb.append("Number of query mix runs (without warmups): "
				+ queryMix.getQueryMixRuns() + " times\n");
		sb.append("min/max Querymix runtime: "
				+ String.format(Locale.US, "%.4fs", queryMix
						.getMinQueryMixRuntime())
				+ " / "
				+ String.format(Locale.US, "%.4fs", queryMix
						.getMaxQueryMixRuntime()) + "\n");
		if (multithreading) {
			sb.append("Total runtime (sum):    "
					+ String.format(Locale.US, "%.3f", queryMix
							.getTotalRuntime()) + " seconds\n");
			sb.append("Total actual runtime:   "
					+ String.format(Locale.US, "%.3f", queryMix
							.getMultiThreadRuntime()) + " seconds\n");
			singleMultiRatio = queryMix.getTotalRuntime()
					/ queryMix.getMultiThreadRuntime();
		} else
			sb.append("Total runtime:          "
					+ String.format(Locale.US, "%.3f", queryMix
							.getTotalRuntime()) + " seconds\n");
		if (multithreading)
			sb.append("QMpH:                   "
					+ String.format(Locale.US, "%.2f", queryMix
							.getMultiThreadQmpH()) + " query mixes per hour\n");
		else
			sb.append("QMpH:                   "
					+ String.format(Locale.US, "%.2f", queryMix.getQmph())
					+ " query mixes per hour\n");
		sb.append("CQET:                   "
				+ String.format(Locale.US, "%.5f", queryMix.getCQET())
				+ " seconds average runtime of query mix\n");
		sb.append("CQET (geom.):           "
				+ String.format(Locale.US, "%.5f", queryMix
						.getQueryMixGeometricMean())
				+ " seconds geometric mean runtime of query mix\n");

		if (all) {
			sb.append("\n");
			// Print per query statistics
			Query[] queries = queryMix.getQueries();
			double[] qmin = queryMix.getQmin();
			double[] qmax = queryMix.getQmax();
			double[] qavga = queryMix.getAqet();// Arithmetic mean
			double[] avgResults = queryMix.getAvgResults();
			double[] qavgg = queryMix.getGeoMean();
			int[] qTimeout = queryMix.getTimeoutsPerQuery();
			int[] minResults = queryMix.getMinResults();
			int[] maxResults = queryMix.getMaxResults();
			int[] nrq = queryMix.getRunsPerQuery();
			for (int i = 0; i < qmin.length; i++) {
				if (queries[i] != null) {
					sb.append("Metrics for Query:      " + (i + 1) + "\n");
					sb.append("Count:                  " + nrq[i]
							+ " times executed in whole run\n");
					sb.append("AQET:                   "
							+ String.format(Locale.US, "%.6f", qavga[i])
							+ " seconds (arithmetic mean)\n");
					sb.append("AQET(geom.):            "
							+ String.format(Locale.US, "%.6f", qavgg[i])
							+ " seconds (geometric mean)\n");
					if (multithreading)
						sb.append("QPS:                    "
								+ String.format(Locale.US, "%.2f",
										singleMultiRatio / qavga[i])
								+ " Queries per second\n");
					else
						sb
								.append("QPS:                    "
										+ String.format(Locale.US, "%.2f",
												1 / qavga[i])
										+ " Queries per second\n");
					sb
							.append("minQET/maxQET:          "
									+ String
											.format(Locale.US, "%.8fs", qmin[i])
									+ " / "
									+ String
											.format(Locale.US, "%.8fs", qmax[i])
									+ "\n");
					if (queries[i].getQueryType() == Query.SELECT_TYPE) {
						sb.append("Average result count:   "
								+ String.format(Locale.US, "%.2f",
										avgResults[i]) + "\n");
						sb.append("min/max result count:   " + minResults[i]
								+ " / " + maxResults[i] + "\n");
					} else {
						sb.append("Average result (Bytes): "
								+ String.format(Locale.US, "%.2f",
										avgResults[i]) + "\n");
						sb.append("min/max result (Bytes): " + minResults[i]
								+ " / " + maxResults[i] + "\n");
					}
					sb
							.append("Number of timeouts:     " + qTimeout[i]
									+ "\n\n");
				}
			}
		}

		return sb.toString();
	}

	/*
	 * Get XML Result String
	 */
	public String printXMLResults(boolean all) {
		StringBuffer sb = new StringBuffer(100);
		double singleMultiRatio = 0.0;

		sb.append("<?xml version=\"1.0\"?>");
		sb.append("<bsbm>\n");
		sb.append("  <querymix>\n");
		sb.append("     <scalefactor>" + parameterPool.getScalefactor()
				+ "</scalefactor>\n");
		sb.append("     <warmups>" + warmups + "</warmups>\n");
		if (multithreading)
			sb.append("     <nrthreads>" + nrThreads + "</nrthreads>\n");
		sb.append("     <seed>" + seed + "</seed>\n");
		sb.append("     <querymixruns>" + queryMix.getQueryMixRuns()
				+ "</querymixruns>\n");
		sb.append("     <minquerymixruntime>"
				+ String.format(Locale.US, "%.4f", queryMix
						.getMinQueryMixRuntime()) + "</minquerymixruntime>\n");
		sb.append("     <maxquerymixruntime>"
				+ String.format(Locale.US, "%.4f", queryMix
						.getMaxQueryMixRuntime()) + "</maxquerymixruntime>\n");
		if (multithreading) {
			sb.append("     <totalruntime>"
					+ String.format(Locale.US, "%.3f", queryMix
							.getTotalRuntime()) + "</totalruntime>\n");
			sb.append("     <actualtotalruntime>"
					+ String.format(Locale.US, "%.3f", queryMix
							.getMultiThreadRuntime())
					+ "</actualtotalruntime>\n");
			singleMultiRatio = queryMix.getTotalRuntime()
					/ queryMix.getMultiThreadRuntime();
		} else {
			sb.append("     <totalruntime>"
					+ String.format(Locale.US, "%.3f", queryMix
							.getTotalRuntime()) + "</totalruntime>\n");
			singleMultiRatio = 1;
		}

		if (multithreading)
			sb.append("     <qmph>"
					+ String.format(Locale.US, "%.2f", queryMix
							.getMultiThreadQmpH()) + "</qmph>\n");
		else
			sb.append("     <qmph>"
					+ String.format(Locale.US, "%.2f", queryMix.getQmph())
					+ "</qmph>\n");
		sb.append("     <cqet>"
				+ String.format(Locale.US, "%.5f", queryMix.getCQET())
				+ "</cqet>\n");
		sb.append("     <cqetg>"
				+ String.format(Locale.US, "%.5f", queryMix
						.getQueryMixGeometricMean()) + "</cqetg>\n");
		sb.append("  </querymix>\n");

		if (all) {
			sb.append("  <queries>\n");
			// Print per query statistics
			Query[] queries = queryMix.getQueries();
			double[] qmin = queryMix.getQmin();
			double[] qmax = queryMix.getQmax();
			double[] qavga = queryMix.getAqet();
			double[] avgResults = queryMix.getAvgResults();
			double[] qavgg = queryMix.getGeoMean();
			int[] qTimeout = queryMix.getTimeoutsPerQuery();
			int[] minResults = queryMix.getMinResults();
			int[] maxResults = queryMix.getMaxResults();
			int[] nrq = queryMix.getRunsPerQuery();
			for (int i = 0; i < qmin.length; i++) {
				if (queries[i] != null) {
					sb.append("    <query nr=\"" + (i + 1) + "\">\n");
					sb.append("      <executecount>" + nrq[i]
							+ "</executecount>\n");
					sb.append("      <aqet>"
							+ String.format(Locale.US, "%.6f", qavga[i])
							+ "</aqet>\n");
					sb.append("      <aqetg>"
							+ String.format(Locale.US, "%.6f", qavgg[i])
							+ "</aqetg>\n");
					sb.append("      <qps>"
							+ String.format(Locale.US, "%.2f", singleMultiRatio
									/ qavga[i]) + "</qps>\n");
					sb.append("      <minqet>"
							+ String.format(Locale.US, "%.8f", qmin[i])
							+ "</minqet>\n");
					sb.append("      <maxqet>"
							+ String.format(Locale.US, "%.8f", qmax[i])
							+ "</maxqet>\n");
					sb.append("      <avgresults>"
							+ String.format(Locale.US, "%.2f", avgResults[i])
							+ "</avgresults>\n");
					sb.append("      <minresults>" + minResults[i]
							+ "</minresults>\n");
					sb.append("      <maxresults>" + maxResults[i]
							+ "</maxresults>\n");
					sb.append("      <timeoutcount>" + qTimeout[i]
							+ "</timeoutcount>\n");
					sb.append("    </query>\n");
				} else {
					sb.append("    <query nr=\"" + (i + 1) + "\">\n");
					sb.append("      <executecount>0</executecount>\n");
					sb.append("      <aqet>0.0</aqet>\n");
					sb.append("    </query>\n");
				}
			}
			sb.append("  </queries>\n");
		}
		sb.append("</bsbm>\n");
		return sb.toString();
	}

	/*
	 * print command line options
	 */
	protected void printUsageInfos() {
		String output = "Usage: java benchmark.testdriver.TestDriver <options> SPARQL-Endpoint\n\n"
				+ "SPARQL-Endpoint: The URL of the HTTP SPARQL Endpoint\n\n"
				+ "Possible options are:\n"
				+ "\t-runs <number of query mix runs>\n" + "\t\tdefault: "
				+ TestDriverDefaultValues.nrRuns
				+ "\n"
				+ "\t-idir <data input directory>\n"
				+ "\t\tThe input directory for the Test Driver data\n"
				+ "\t\tdefault: "
				+ TestDriverDefaultValues.resourceDir
				+ "\n"
				+ "\t-ucf <use case file name>\n"
				+ "\t\tSpecifies where the use case description file can be found.\n"
				+ "\t\tdefault: "
				+ TestDriverDefaultValues.usecaseFile
				+ "\n"
				+ "\t-w <number of warm up runs before actual measuring>\n"
				+ "\t\tdefault: "
				+ TestDriverDefaultValues.warmups
				+ "\n"
				+ "\t-o <benchmark results output file>\n"
				+ "\t\tdefault: "
				+ TestDriverDefaultValues.xmlResultFile
				+ "\n"
				+ "\t-dg <Default Graph>\n"
				+ "\t\tdefault: "
				+ TestDriverDefaultValues.defaultGraph
				+ "\n"
				+ "\t-sql\n"
				+ "\t\tuse JDBC connection to a RDBMS. Instead of a SPARQL-Endpoint, a JDBC URL has to be supplied.\n"
				+ "\t\tdefault: not set\n"
				+ "\t-mt <Number of clients>\n"
				+ "\t\tRun multiple clients concurrently.\n"
				+ "\t\tdefault: not set\n"
				+ "\t-seed <Long Integer>\n"
				+ "\t\tInit the Test Driver with another seed than the default.\n"
				+ "\t\tdefault: "
				+ TestDriverDefaultValues.seed
				+ "\n"
				+ "\t-t <timeout in ms>\n"
				+ "\t\tTimeouts will be logged for the result report.\n"
				+ "\t\tdefault: "
				+ TestDriverDefaultValues.timeoutInMs
				+ "ms\n"
				+ "\t-dbdriver <DB-Driver Class Name>\n"
				+ "\t\tdefault: "
				+ TestDriverDefaultValues.driverClassName
				+ "\n"
				+ "\t-u <Sparql Update Service Endpoint URL>\n"
				+ "\t\tUse this if you have SPARQL Update queries in your query mix.\n"
				+ "\t-udataset <update dataset file name>\n"
				+ "\t\tSpecified an update file generated by the BSBM dataset generator.\n"
				+ "\t-q\n"
				+ "\t\tTurn on qualification mode instead of doing a test run.\n"
				+ "\t\tdefault: "
				+ TestDriverDefaultValues.qualification
				+ "\n"
				+ "\t-qf <qualification file name>\n"
				+ "\t\tTo change the  filename from its default.\n"
				+ "\t\tdefault: "
				+ TestDriverDefaultValues.qualificationFile
				+ "\n"
				+ "\t-rampup\n"
				+ "\t\tRun ramp-up to reach steady state.\n"
				+ "\t-uqp <update query parameter>\n"
				+ "\t\tThe forms parameter name for the query string.\n"
				+ "\t\tdefault: "
				+ TestDriverDefaultValues.updateQueryParameter
				+ "\n";

		System.out.print(output);
	}

	static class TestDriverShutdown extends Thread {
		TestDriver testdriver;

		TestDriverShutdown(TestDriver t) {
			this.testdriver = t;
		}

		@Override
		public void run() {
			try {
				if (testdriver.server != null)
					testdriver.server.close();
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}

	public static void main(String ...argv) {
        JenaSystem.init();
        System.out.println("TDB = "+Jena.VERSION);

	    if ( FileOps.exists("log4j.properties") )
	        PropertyConfigurator.configureAndWatch("log4j.properties", 60*1000);
	    else
	        DOMConfigurator.configureAndWatch("log4j.xml", 60 * 1000);
		TestDriver testDriver = new TestDriver(argv);
		testDriver.init();
		System.out.println("\nStarting test...\n");
		boolean printableResults = false ; 
		if (testDriver.multithreading) {
			testDriver.runMT();
			printableResults = true ;
		} else if (testDriver.qualification)
			testDriver.runQualification();
		else if (testDriver.rampup)
			testDriver.runRampup();
		else {
			testDriver.run();
			printableResults = true ;
		}

		if ( false && printableResults )
		    System.out.println("\n" + testDriver.printResults(true));
		
	}
}
