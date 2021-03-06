/*
* Copyright 2019
* Ubiquitous Knowledge Processing (UKP) Lab
* Technische Universität Darmstadt
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*      http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package de.tudarmstadt.ukp.naacl2019.argannotation.crowdsourcing.goldestimator.mace.claim;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.apache.uima.UIMAException;
import org.apache.uima.analysis_engine.AnalysisEngineProcessException;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import de.tudarmstadt.ukp.naacl2019.argannotation.crowdsourcing.goldestimator.mace.MACE;

public class MaceClaimPremisePredictor
{

    public static double MACE_THRESHOLD = 1.00;

    @Option(name="-o",aliases = { "--output" },metaVar="dir",usage="output folder", required=true)
	private File outputDir;

	@Option(name="-i",aliases = { "--input" },metaVar="dir",usage="input folder", required=true)
	private File inputDir;

	@Option(name="-r",aliases = { "--resultFile" },metaVar="file",usage="AMT .result file", required=true)
	private File resultFile;

	public static void main(String[] args)
            throws Exception
    {
		new MaceClaimPremisePredictor().doMain(args);

    }

    private void doMain(String[] args) throws UIMAException, IOException
    {
    	CmdLineParser parser = new CmdLineParser(this);
		try {
            // parse the arguments.
            parser.parseArgument(args);
            doMacePredict(inputDir, outputDir,resultFile.getPath());
        } catch( CmdLineException e ) {
            System.err.println(e.getMessage());
            System.err.println("java "+this.getClass().getSimpleName()+" [options...] arguments...");
            parser.printUsage(System.err);
            System.err.println();
            return;
        }

    }

    /**
     * Merge all the splitted mace files into one big file and do the overall predictions.
     * Afterwards the files are splitted again and written as the results are written.
     *
     * @param inputDir
     *            The input directory of the mace formatted files
     * @param outputDir
     *            The output directory of the mace splitted mace prediction files
     * @param maceFolder
     * @throws AnalysisEngineProcessException
     */
    public static void doMacePredict(File inputDir, File outputDir, String maceFolder)
        throws AnalysisEngineProcessException
    {
        // List all mace files in the input directory
        List<File> files = new ArrayList<>(FileUtils.listFiles(inputDir, new String[] { "csv" },
                false));
        BufferedReader br;
        BufferedWriter bw;
        // Output files for MACE to work with
        File inputFile = new File(maceFolder + "/" + "inputFile.csv");
        File outputPredictionFile = new File(maceFolder + "/" + "predictions.csv");
        File outputDistributionPredictFile = new File(maceFolder + "/" + "outputDistributionPredictFile");
        File outputCompetenceFile = new File(maceFolder + "/" + "Competence.csv");
        // List of filename - content mapping for the splitting later
        List<FilePair<String, List<String>>> fileMapping = new ArrayList<FilePair<String, List<String>>>();
        // Create the mace input file
        try {
            bw = new BufferedWriter(new FileWriter(inputFile));
            for (File splitInput : files) {
                br = new BufferedReader(new FileReader(splitInput));
                String fileName = splitInput.getName();
                // ignore input files from previous steps
                if (fileName.equals("inputFile.csv") || fileName.equals("competence.csv")
                        || fileName.equals("predictions.csv") || fileName.equals("bioInputFile.csv") || fileName.equals("bioOutputFile.csv") || fileName.equals("stanceOutput.csv")  || fileName.equals("stanceCompetence.csv")  || fileName.equals("stanceInputFile.csv")) {
                    continue;
                }
                List<String> fileContent = new ArrayList<String>();
                String line;
                while ((line = br.readLine()) != null) {
                    fileContent.add(line);
                    bw.write(line + System.getProperty("line.separator"));
                }
                br.close();
                fileMapping.add(new FilePair<String, List<String>>(fileName, fileContent));
            }

            bw.close();

            // MACE step, predict B/I/O Tag and stance in a single step:
            MACE.main(new String[] { "--threshold", Double.toString(MACE_THRESHOLD), "--iterations",
                    "500", "--distribution", "--restarts", "10", "--outputPredictions",
                    outputDistributionPredictFile.getAbsolutePath(), "--outputCompetence",
                    outputCompetenceFile.getAbsolutePath(), inputFile.getAbsolutePath() });

	    // read the distribution file and predict BIO tags as follows:
	    // if p(B) + p(I) > p(O) -> I
	    //     if p(B) > p(I)    -> B
	    //     else              -> I
	    // else 		     -> O

            String bioPredictionString = "";
            List<String> distribution = FileUtils.readLines(outputDistributionPredictFile, "utf-8");
            double previousBValue = 0;
            boolean previousBTag = false;
            for (String distributionLine : distribution) {
                String predictedTag = "";
                List<String> tags = Arrays.asList(distributionLine.split("\t"));
                // get the probability values of the bio tags
                double oTag = 0.0;
                double bTag = 0.0;
                double iTag = 0.0;
                double aStance = 0.0;
                double sStance = 0.0;
                for (String tag : tags) {
                    List<String> tmpSplit = Arrays.asList(tag.split(" "));
                    if (tmpSplit.get(0).equals("O")) {
                        oTag = Double.valueOf(tmpSplit.get(1));
                    }
                    // We do not distinguish between attacking and supporting stances for B/I/O prediction
                    if (tmpSplit.get(0).contains("I")) {
                        iTag += Double.valueOf(tmpSplit.get(1));
                    }
                    if (tmpSplit.get(0).contains("B")) {
                        bTag += Double.valueOf(tmpSplit.get(1));
                    }
                    if (tmpSplit.get(0).contains("A")) {
                    	aStance += Double.valueOf(tmpSplit.get(1));
                    }
                    if (tmpSplit.get(0).contains("S")) {
                    	sStance += Double.valueOf(tmpSplit.get(1));
                    }
                }
                // do the calculation of the tag as described above
                double biTag = bTag + iTag;
                if (oTag > biTag) {
                    predictedTag = "O";
                    previousBTag=false;
                }
                else if (bTag > iTag) {
                    // We use > for the B-tag , since we rather have standalone I-tags than too many
                    // B-tags. Standalone I-tags should be handled in the post processing
                    if(previousBTag){
                        predictedTag = "B";
                        if(bTag<previousBValue){
                            predictedTag="I";
                            previousBTag=false;
                        }
                    }else{
                        predictedTag = "B";
                        previousBTag=true;
                        previousBValue = bTag;
                    }
                }
                else {
                    predictedTag = "I";
                    previousBTag=false;
                }
                // Add stance to the predicted tag if it is not O
                if(!predictedTag.equals("O")){
                	if(aStance > sStance){
                		predictedTag += "-A";
                	}else{
                		predictedTag += "-S";
                	}
                }
                bioPredictionString+=predictedTag + ",";
            }
            // Post processing, if there is an annotation starting with I, replace it with B.
            bioPredictionString.replaceAll("O,I", "O,B");
            // Replace B-B tags with O-B, since B-B -> B-I gets taken care of before
            bioPredictionString.replaceAll("B-.,B", "O,B");

            // Write the final prediction in the B-S/A , I-S/A , O format
            bw = new BufferedWriter(new FileWriter(outputPredictionFile.getAbsolutePath()));
            List<String> bioPredictions = new ArrayList<String>(Arrays.asList(bioPredictionString.substring(0,bioPredictionString.length()-1).split(",")));
            for (String bioTag : bioPredictions) {

                bw.write(bioTag + System.getProperty("line.separator"));
            }
            bw.close();
            // read back the predictions and competence
            List<String> predictions = FileUtils.readLines(outputPredictionFile, "utf-8");

            // Write the splitted prediction files
            int index = 0;
            for (FilePair<String, List<String>> pair : fileMapping) {
                String fileName = pair.getKey();
                bw = new BufferedWriter(
                        new FileWriter(outputDir.getAbsolutePath() + "/" + fileName));
                // Write one line of the prediction for every element in the prediction array.
                for (@SuppressWarnings("unused") String element : pair.getValue()) {
                    bw.write(predictions.get(index) + System.getProperty("line.separator"));
                    index++;
                }
                bw.close();
            }

            String competenceRaw = FileUtils.readFileToString(outputCompetenceFile, "utf-8");
            String[] competence = competenceRaw.split("\t");
            List<String> workerIDs = FileUtils.readLines(
                    new File(inputDir + "/" + "workerIDs.txt"), "utf-8");
            if (competence.length != workerIDs.size()) {
                throw new IllegalStateException("Expected " + workerIDs.size()
                        + " competence number, got " + competence.length);
            }
            // Write the competence file:
            bw = new BufferedWriter(new FileWriter(new File(outputDir.getAbsoluteFile() + "/" + "overallWorkerCompetence.csv")));
            for(int i=0;i<workerIDs.size();i++){
                bw.write(workerIDs.get(i) + "," + competence[i] + System.getProperty("line.separator"));
            }
            bw.close();
        }
        catch (IOException e) {
            throw new AnalysisEngineProcessException(e);
        }

    }

}

final class FilePair<K, V>
    implements Map.Entry<K, V>
{
    private final K key;
    private V value;

    public FilePair(K key, V value)
    {
        this.key = key;
        this.value = value;
    }

    @Override
    public K getKey()
    {
        return key;
    }

    @Override
    public V getValue()
    {
        return value;
    }

    @Override
    public V setValue(V value)
    {
        V old = this.value;
        this.value = value;
        return old;
    }
}
