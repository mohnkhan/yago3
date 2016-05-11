package fromWikipedia;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import basics.Fact;
import basics.RDFS;
import extractors.MultilingualWikipediaExtractor;
import fromOtherSources.PatternHardExtractor;
import fromOtherSources.WikidataLabelExtractor;
import fromThemes.PersonNameExtractor;
import javatools.administrative.Announce;
import javatools.datatypes.FinalSet;
import javatools.filehandlers.FileLines;
import javatools.parsers.Char17;
import javatools.util.FileUtils;
import utils.MultilingualTheme;
import utils.PatternList;
import utils.Theme;
import utils.TitleExtractor;

/**
 * Extracts link likelihood for mentions from Wikipedia.
 *
 * @author Johannes Hoffart
 * @author Dominic Seyler (dseyler@mpi-inf.mpg.de)
 * 
 */
public class MentionLinkLikelihoodExtractor extends MultilingualWikipediaExtractor {
	
  /**
   * counts the link statistics for all tokens found in Wikipedia articles
   * int[0]: how many times has the token been found as link in Wikipedia articles
   * int[1]: how many times has the token been found in general in Wikipeida articles
   */
  private static Map<String, int[]> mentionTokensLinkCount = new HashMap<>();
  
  //Extract set of all link surface forms via regex.
  // [[target|linkname]] or [[target]]
  private static final String linkRegex = "\\[\\[(.*?)\\]\\]";
  private static final Pattern linkPattern = Pattern.compile(linkRegex);
  
  // match links with anchor text [[target|linkname]]
  private static final String anchorTextRegex = "\\|(.*$)";
  private static final Pattern anchorTextPattern = Pattern.compile(anchorTextRegex);
	
  @Override
  public Set<Theme> input() {
    Set<Theme> input = new HashSet<>();
    
    input.add(StructureExtractor.STRUCTUREFACTS.inLanguage(language)); // also gives links and anchor texts.
    input.add(DisambiguationPageExtractor.DISAMBIGUATIONMEANSFACTS.inLanguage(language));
    input.add(RedirectExtractor.REDIRECTFACTS.inLanguage(language));
	input.add(PersonNameExtractor.PERSONNAMES);
	input.add(PersonNameExtractor.PERSONNAMEHEURISTICS);
	input.add(GenderExtractor.PERSONS_GENDER);
	input.add(WikidataLabelExtractor.WIKIPEDIALABELS);
	input.add(WikidataLabelExtractor.WIKIDATAMULTILABELS);
	
    // TODO hasGivenName hasFamilyName
    
    return input;
  }
  
  private void loadMentions() throws IOException {	  
	  List<Fact> fs = DisambiguationPageExtractor.DISAMBIGUATIONMEANSFACTS.inLanguage(language).factCollection().getFactsWithRelation(RDFS.label);
	  
	  addFacts(fs);

	  fs  = RedirectExtractor.REDIRECTFACTS.inLanguage(language).factCollection().getFactsWithRelation("<redirectedFrom>");
	  
	  addFacts(fs);
	  
//	  fs = StructureExtractor.STRUCTUREFACTS.inLanguage(language).factCollection().getFactsWithRelation("<hasAnchorText>");
	  
//	  addFacts(fs);
  }

  private void addFacts(List<Fact> fs) {
	for (Fact f : fs) {
		  String mention = f.getObjectAsJavaString();
		  mention = clean(mention);
		  for(String mentionToken : mention.split(" ")){
			  if(!mentionTokensLinkCount.containsKey(mentionToken)){
				  mentionTokensLinkCount.put(mentionToken, new int[2]);
			  }
		  }
	  }
  }


  /** Context for entities */
  public static final MultilingualTheme LIKELIHOODFACTS = new MultilingualTheme(
      "mentionLikelihoodFacts",
      "Mention link likelihoods estimated by counting how often a mention occurs linked vs. occurs overall (by article).");
  
  @Override
  public Set<Theme> output() {
    return new FinalSet<Theme>(LIKELIHOODFACTS.inLanguage(language));      
  }

  @Override
  public void extract() throws Exception {	  
    // Extract the information
    Announce.doing("Extracting mention likelihood facts");

    BufferedReader in = FileUtils.getBufferedUTF8Reader(wikipedia);
    TitleExtractor titleExtractor = new TitleExtractor(language);

    PatternList replacements = new PatternList(PatternHardExtractor.AIDACLEANINGPATTERNS.factCollection(), "<_aidaCleaning>");

    // Load all mentions.
    loadMentions();
    
    int pagesProcessed = 0;
    
    String titleEntity = null;
    while (true) {
      switch (FileLines.findIgnoreCase(in, "<title>")) {
        case -1:
          // Write out likelihood scores.
          for(String mentionToken : mentionTokensLinkCount.keySet()){
        	  int[] counts = mentionTokensLinkCount.get(mentionToken);
        	  double linkLikelihood = 0;
        	  if(counts[1] > 0){
        		  linkLikelihood = counts[0] / (double) counts[1];
        	  }
        	  Fact f = new Fact(mentionToken, "<_hasLinkLikelihood>", "\""+ linkLikelihood + "\"^^xsd:double");
        	  LIKELIHOODFACTS.inLanguage(language).write(f);
          }
        	
          Announce.done();
          in.close();
          return;
        case 0:
          pagesProcessed++;
          if(pagesProcessed % 10_000 == 0){
        	  System.out.println("MentionLinkLikelihoodExtractor: " + pagesProcessed + " pages Processed");
          }
        	
          titleEntity = titleExtractor.getTitleEntity(in);
          if (titleEntity == null) continue;

          String page = FileLines.readBetween(in, "<text", "</text>");
          String normalizedPage = Char17.decodeAmpersand(Char17.decodeAmpersand(page.replaceAll("[\\s\\x00-\\x1F]+", " ")));
          String transformedPage = replacements.transform(normalizedPage);
          
          Set<String> pageVocabulary = new HashSet<>(Arrays.asList(clean(transformedPage.replaceAll("\\[\\[.*?\\]\\]", "")).split(" ")));

   		  // extract all linked tokens
  		  List<String> linkedTokens = new ArrayList<>();
  		  Matcher linkMatcher = linkPattern.matcher(transformedPage);
  		  while (linkMatcher.find()) {
  			  for (int i = 0; i < linkMatcher.groupCount(); i++) {
  				  String group = linkMatcher.group(i + 1);
  				  
  				  if(group.contains(":")){
					  continue;
				  }
  				  
  				  String[] split = null;

  				  // Take surface form of links if applicable
  				  if (group.contains("|")) {
  					  Matcher anchorTextMatcher = anchorTextPattern.matcher(group);
  					  if (anchorTextMatcher.find()) {
  						  String anchorText = anchorTextMatcher.group(1);
  						  anchorText = clean(anchorText);
  						  split = anchorText.split(" ");
  				 	  } else {
  						  System.err.println("RegEx for anchor did not match, anchorText = " + group);
  					  }
  				  } else {
  					  group = clean(group);
  					  split = group.split(" ");
  				  }

  				  linkedTokens.addAll(Arrays.asList(split));
  			  }
  		  }
          
          // increase denumerator in token counts for all tokens of the article, 
          // excluding linked tokens
          for(String token : pageVocabulary) {
			if(mentionTokensLinkCount.containsKey(token) && !linkedTokens.contains(token)){
				int[] counts = mentionTokensLinkCount.get(token);
				counts[1]++;
				mentionTokensLinkCount.put(token, counts);
			}
		  }
          
          // increase numerator and denumerator in token counts for all linked tokens
          for (String token : linkedTokens) {
			if(mentionTokensLinkCount.containsKey(token)){
				int[] counts = mentionTokensLinkCount.get(token);
				counts[0]++;
				counts[1]++;
				mentionTokensLinkCount.put(token, counts);
			}
		}          
          break;
          
      }
      
    }
    
    
  }

  /**
   * Needs Wikipedia as input
   * 
   * @param wikipedia
   *            Wikipedia XML dump
   */
  public MentionLinkLikelihoodExtractor(String lang, File wikipedia) {
    super(lang, wikipedia);
  }
  
  /**
   * Turns text to lowercase and removes non alpha-numeric characters
   * 
   * @param text
   * 		   Input text
   */			
  private String clean(String text){
	  return text.replaceAll("[^\\p{L}\\p{N} ]", "");
  }

}
