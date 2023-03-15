package edu.oswego.cs.database;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.bson.types.Binary;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;
import java.io.*;
import java.util.*;
import java.text.DecimalFormat;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Updates.set;

public class PeerReviewAssignmentInterface {
    private final MongoCollection<Document> teamCollection;
    private final MongoCollection<Document> assignmentCollection;
    private final MongoCollection<Document> submissionsCollection;
    MongoDatabase assignmentDB;

    public PeerReviewAssignmentInterface() {
        DatabaseManager databaseManager = new DatabaseManager();
        try {
            MongoDatabase teamDB = databaseManager.getTeamDB();
            assignmentDB = databaseManager.getAssignmentDB();
            teamCollection = teamDB.getCollection("teams");
            assignmentCollection = assignmentDB.getCollection("assignments");
            submissionsCollection = assignmentDB.getCollection("submissions");
        } catch (WebApplicationException e) {
            throw new WebApplicationException(Response.status(Response.Status.BAD_REQUEST).entity("Failed to retrieve collections.").build());
        }
    }

    public void addPeerReviewSubmission(String course_id, int assignment_id, String srcTeamName, String destinationTeam, String fileName, int grade, InputStream fileData) throws IOException {
        Document reviewedByTeam = teamCollection.find(eq("team_id", srcTeamName)).first();
        Document reviewedTeam = teamCollection.find(eq("team_id", destinationTeam)).first();
        Document assignment = assignmentCollection.find(and(eq("course_id", course_id), eq("assignment_id", assignment_id))).first();
        if (assignment == null)
            throw new WebApplicationException(Response.status(Response.Status.BAD_REQUEST).entity("this assignment was not found in this course").build());
        if (reviewedByTeam == null)
            throw new WebApplicationException(Response.status(Response.Status.BAD_REQUEST).entity("no team for this student").build());
        if (reviewedTeam == null)
            throw new WebApplicationException(Response.status(Response.Status.BAD_REQUEST).entity("no team for this student").build());
        if (reviewedByTeam.getList("team_members", String.class) == null)
            throw new WebApplicationException(Response.status(Response.Status.BAD_REQUEST).entity("Members not defined in team").build());
        if (reviewedTeam.getList("team_members", String.class) == null)
            throw new WebApplicationException(Response.status(Response.Status.BAD_REQUEST).entity("Members not defined in team").build());
        if (reviewedByTeam.get("team_id", String.class) == null)
            throw new WebApplicationException(Response.status(Response.Status.BAD_REQUEST).entity("team_id not defined").build());
        if (reviewedTeam.get("team_id", String.class) == null)
            throw new WebApplicationException(Response.status(Response.Status.BAD_REQUEST).entity("team_id not defined").build());

        String reg = "/";
        String path = "assignments" + reg + course_id + reg + assignment_id + reg + "peer-review-submissions";
        Document new_submission = new Document()
                .append("course_id", course_id)
                .append("assignment_id", assignment_id)
                .append("assigment_name", assignment.getString("assignment_name"))
                .append("submission_name", fileName)
                .append("submission_data", Base64.getDecoder().decode(new String(fileData.readAllBytes())))
                .append("reviewed_by", reviewedByTeam.getString("team_id"))
                .append("reviewed_by_members", reviewedByTeam.getList("team_members", String.class))
                .append("reviewed_team", reviewedTeam.getString("team_id"))
                .append("reviewed_team_members", reviewedTeam.getList("team_members", String.class))
                .append("type", "peer_review_submission")
                .append("grade", grade);
        if (submissionsCollection.find(new_submission).iterator().hasNext()) {
            throw new WebApplicationException(Response.status(Response.Status.BAD_REQUEST).entity("submission already exists").build());
        } else submissionsCollection.insertOne(new_submission);

        addCompletedTeam(course_id, assignment_id, srcTeamName, destinationTeam);

    }

    public void addCompletedTeam(String courseID, int assignmentID, String sourceTeam, String targetTeam) {

        Document assignmentDocument = assignmentCollection.find(and(eq("course_id", courseID), eq("assignment_id", assignmentID))).first();
        if (assignmentDocument == null)
            throw new WebApplicationException(Response.status(Response.Status.BAD_REQUEST).entity("Failed to find assignment. As a result the assignment could not update the completed_teams").build());

        Map<String, List<String>> completedTeams = (Map<String, List<String>>) assignmentDocument.get("completed_teams");
        Map<String, List<String>> finalTeams = completedTeams;
        List<String> temp = completedTeams.get(sourceTeam);
        temp.add(targetTeam);
        finalTeams.put(sourceTeam, temp);
        assignmentDocument.replace("completed_teams", completedTeams, finalTeams);
        assignmentCollection.replaceOne(and(eq("course_id", courseID), eq("assignment_id", assignmentID)), assignmentDocument);
        assignmentDocument = assignmentCollection.find(and(eq("course_id", courseID), eq("assignment_id", assignmentID))).first();
        completedTeams = (Map<String, List<String>>) assignmentDocument.get("completed_teams");
        int currentNumOfReviews = 0;
        for (Map.Entry<String, List<String>> entry : completedTeams.entrySet()) {
            List<String> list = entry.getValue();
            for (String s : list) {
                if (s.equals(targetTeam))
                    currentNumOfReviews++;
            }
        }
        if (currentNumOfReviews == (int) assignmentDocument.get("reviews_per_team")) {
            makeFinalGrade(courseID, assignmentID, targetTeam);
        }
        if (assignmentDocument.get("completed_teams") == assignmentDocument.get("assigned_teams")) {
            assignmentCollection.findOneAndUpdate(and(eq("course_id", courseID), eq("assignment_id", assignmentID)), set("grade_finalized", true));
        }
    }

    public String downloadFinishedPeerReviewName(String courseID, int assignmentID, String srcTeamName, String destTeamName) {
        Document submittedPeerReview = submissionsCollection.find(and(eq("type", "peer_review_submission"), eq("assignment_id", assignmentID), eq("course_id", courseID), eq("reviewed_by", srcTeamName), eq("reviewed_team", destTeamName))).first();
        if (submittedPeerReview==null)
            throw new WebApplicationException("No peer review from team " + srcTeamName + " for " + destTeamName);
        return (String) submittedPeerReview.get("submission_name");

    }

    public Binary downloadFinishedPeerReview(String courseID, int assignmentID, String srcTeamName, String destTeamName) {
        Document submittedPeerReview = submissionsCollection.find(and(eq("type", "peer_review_submission"), eq("assignment_id", assignmentID), eq("course_id", courseID), eq("reviewed_by", srcTeamName), eq("reviewed_team", destTeamName))).first();
        if (submittedPeerReview==null)
            throw new WebApplicationException("No peer review from team " + srcTeamName + " for " + destTeamName);
        return (Binary) submittedPeerReview.get("submission_data");

    }

    public List<Document> getUsersReviewedAssignment(String courseID, int assignmentID, String studentID) {
        MongoCursor<Document> query = submissionsCollection.find(and(eq("course_id", courseID),
                eq("assignment_id", assignmentID),
                eq("reviewed_team_members", studentID),
                eq("type", "peer_review_submission"))).iterator();
        List<Document> assignments = new ArrayList<>();
        while (query.hasNext()) {
            Document document = query.next();
            assignments.add(document);
        }
        if (assignments.isEmpty())
            throw new WebApplicationException(Response.status(Response.Status.BAD_REQUEST).entity("Assignment does not exist").build());
        return assignments;
    }

    public List<Document> getUsersReviewedAssignment(String courseID, String studentID) {
        MongoCursor<Document> query = submissionsCollection.find(and(eq("course_id", courseID),
                eq("reviewed_team_members", studentID),
                eq("type", "peer_review_submission"))).iterator();
        List<Document> assignments = new ArrayList<>();
        while (query.hasNext()) {
            Document document = query.next();
            assignments.add(document);
        }
        if (assignments.isEmpty())
            throw new WebApplicationException(Response.status(Response.Status.BAD_REQUEST).entity("Assignment does not exist").build());
        return assignments;
    }

    public List<Document> getAssignmentsReviewedByUser(String courseID, String studentID) {
        MongoCursor<Document> query = submissionsCollection.find(and(
                eq("course_id", courseID),
                eq("reviewed_by_members", studentID),
                eq("type", "peer_review_submission"))).iterator();
        List<Document> assignments = new ArrayList<>();
        while (query.hasNext()) {
            Document document = query.next();
            assignments.add(document);
        }
        if (assignments.isEmpty())
            throw new WebApplicationException(Response.status(Response.Status.BAD_REQUEST).entity("Assignment does not exist").build());
        return assignments;
    }

    public List<String> getAssignedTeams(String courseID, int assignmentID, String teamName) {
        Document assignmentDocument = assignmentCollection.find(and(
                eq("course_id", courseID),
                eq("assignment_id", assignmentID))).first();
        if (assignmentDocument == null) throw new WebApplicationException("Course/Assignment ID does not exist.");
        Document teamAssignmentDocument = (Document) assignmentDocument.get("assigned_teams");
        return teamAssignmentDocument.getList(teamName, String.class);
    }

    public List<String> filterBySubmitted(List<String> allTeams, String course_id, int assignment_id) {
        List<String> finalTeams = new ArrayList<>();
        for (String teamName : allTeams) {
            if (submissionsCollection.find(and(
                    eq("course_id", course_id),
                    eq("assignment_id", assignment_id),
                    eq("team_name", teamName))).iterator().hasNext()) {
                finalTeams.add(teamName);
            }
        }
        return finalTeams;
    }

    public List<String> getCourseTeams(String courseID) {
        ArrayList<String> teamNames = new ArrayList<>();
        for (Document teamDocument : teamCollection.find(eq("course_id", courseID))) {
            String teamName = (String) teamDocument.get("team_id");
            teamNames.add(teamName);
        }
        return teamNames;
    }

    public Document addAssignedTeams(Map<String, List<String>> peerReviewAssignments, String courseID, int assignmentID) {

        Document assignmentDocument = assignmentCollection.find(and(eq("course_id", courseID), eq("assignment_id", assignmentID))).first();
        if (assignmentDocument == null)
            throw new WebApplicationException(Response.status(Response.Status.BAD_REQUEST).entity("Failed to add assigned teams").build());

        Document doc = new Document();
        Document completedTeamsDoc = new Document();
        for (String team : peerReviewAssignments.keySet()) {
            doc.put(team, peerReviewAssignments.get(team));
            completedTeamsDoc.put(team, new ArrayList<>());
        }
        assignmentCollection.updateOne(assignmentDocument, set("assigned_teams", doc));
        assignmentCollection.updateOne(assignmentDocument, set("completed_teams", completedTeamsDoc));

        return doc;
    }

    public void addAllTeams(List<String> allTeams, String courseID, int assignmentID, int reviewsPerTeam) {
        Document result = assignmentCollection.findOneAndUpdate(and(eq("course_id", courseID), eq("assignment_id", assignmentID)), set("all_teams", allTeams));
        if (result == null)
            throw new WebApplicationException(Response.status(Response.Status.BAD_REQUEST).entity("Failed to set all teams to assignment").build());
        result = assignmentCollection.findOneAndUpdate(and(eq("course_id", courseID), eq("assignment_id", assignmentID)), set("reviews_per_team", reviewsPerTeam));
        if (result == null)
            throw new WebApplicationException(Response.status(Response.Status.BAD_REQUEST).entity("Failed to add number of reviews per team to database document").build());
    }

    public void addDistroToSubmissions(Map<String, List<String>> distro, String course_id, int assignment_id) {
        for (String team : distro.keySet()) {
            Document result = submissionsCollection.findOneAndUpdate(and(
                            eq("course_id", course_id),
                            eq("assignment_id", assignment_id),
                            eq("team_name", team), eq("type", "team_submission")),
                    set("reviews", distro.get(team)));
            if (result == null)
                throw new WebApplicationException(Response.status(Response.Status.BAD_REQUEST).entity("Failed to add teams to submission: " + team).build());
        }
    }

    public List<String> getTeams(String courseID, int assignmentID) {
        Document assignment = assignmentCollection.find(and(eq("course_id", courseID), eq("assignment_id", assignmentID))).first();
        if (assignment == null)
            throw new WebApplicationException(Response.status(Response.Status.BAD_REQUEST).entity("No assignment found.").build());
        List<String> teams = assignment.getList("all_teams", String.class);
        if (teams == null)
            throw new WebApplicationException(Response.status(Response.Status.BAD_REQUEST).entity("All teams not found for: " + courseID).build());
        return teams;
    }

    public Document getTeamGrades(String courseID, int assignmentID, String teamName) {
        Document team = submissionsCollection.find(and(
                eq("course_id", courseID),
                eq("assignment_id", assignmentID),
                eq("team_name", teamName),
                eq("type", "team_submission"))).first();
        if (team == null)
            throw new WebApplicationException(Response.status(Response.Status.BAD_REQUEST).entity("Team not found.").build());
        List<String> reviews = team.getList("reviews", String.class);
        List<Document> teams = new ArrayList<>();
        for (String reviewTeam : reviews) {
            Document result = submissionsCollection.find(and(
                    eq("reviewed_by", reviewTeam),
                    eq("reviewed_team", teamName),
                    eq("course_id", courseID),
                    eq("assignment_id", assignmentID),
                    eq("type", "peer_review_submission")
            )).first();
            Document document = new Document().append("team_name", reviewTeam);
            if (result == null) document.append("grade_given", "pending");
            else document.append("grade_given", result.getInteger("grade"));
            teams.add(document);
        }
        return new Document().append("teams", teams);
    }

    public Document professorUpdate(String courseID, int assignmentID, String teamName, int grade) {
        Document team = submissionsCollection.findOneAndUpdate(and(
                        eq("course_id", courseID),
                        eq("assignment_id", assignmentID),
                        eq("team_name", teamName),
                        eq("type", "team_submission")),
                set("grade", grade));
        if (team == null)
            throw new WebApplicationException(Response.status(Response.Status.BAD_REQUEST).entity("Team not found.").build());
        return team;
    }

    public void makeFinalGrade(String courseID, int assignmentID, String teamName) {
        Document assignment = assignmentCollection.find(and(eq("course_id", courseID), eq("assignment_id", assignmentID))).first();
        if (assignment == null)
            throw new WebApplicationException(Response.status(Response.Status.BAD_REQUEST).entity("Assignment not found.").build());
        int points = assignment.getInteger("points");
        Document team_submission = submissionsCollection.find(and(
                eq("course_id", courseID),
                eq("assignment_id", assignmentID),
                eq("team_name", teamName),
                eq("type", "team_submission"))).first();

        List<String> teams_that_graded = team_submission.getList("reviews", String.class);


        if (teams_that_graded == null)
            throw new WebApplicationException(Response.status(Response.Status.BAD_REQUEST).entity("Assigned teams not found for: " + teamName + "for assignment: " + assignmentID).build());
        int total_points = 0;
        int count_of_reviews_submitted = teams_that_graded.size();

        //my code
        String[] temp = new String[count_of_reviews_submitted];
        int counter = 0;
        for(String teamsThatGraded : teams_that_graded){
            temp[counter] = teamsThatGraded;
            counter++;
        }
        int currentTeam = 0;
        for (String review : teams_that_graded) {
            Document team_review = submissionsCollection.find(and(
                    eq("course_id", courseID),
                    eq("assignment_id", assignmentID),
                    eq("reviewed_by", review),
                    eq("reviewed_team", teamName),
                    eq("type", "peer_review_submission"))).first();
            if (team_review == null) {
                count_of_reviews_submitted--;
            } else {
                if (team_review.get("grade", Integer.class) == null) {
                    throw new WebApplicationException(Response.status(Response.Status.BAD_REQUEST).entity("team: " + review + "'s review has no points.").build());
                } else {
                    total_points += team_review.get("grade", Integer.class);

                }
            }
            currentTeam++;
        }
        DecimalFormat tenth = new DecimalFormat("0.##");
        double final_grade = Double.parseDouble(tenth.format((((double) total_points / count_of_reviews_submitted) / points) * 100));//round



        submissionsCollection.findOneAndUpdate(team_submission, set("grade", final_grade));
    }

    public void makeFinalGrades(String courseID, int assignmentID) {
        Document assignment = assignmentCollection.find(and(eq("course_id", courseID), eq("assignment_id", assignmentID))).first();
        if (assignment == null)
            throw new WebApplicationException(Response.status(Response.Status.BAD_REQUEST).entity("Assignment not found.").build());
        List<String> allTeams = assignment.getList("all_teams", String.class);
        int points = assignment.getInteger("points");
        for (String team : allTeams) {
            Document team_submission = submissionsCollection.find(and(
                    eq("course_id", courseID),
                    eq("assignment_id", assignmentID),
                    eq("team_name", team),
                    eq("type", "team_submission"))).first();

            if (team_submission == null) {
                Document blankSubmission = new Document()
                        .append("course_id", courseID)
                        .append("assignment_id", assignmentID)
                        .append("team_name", team)
                        .append("type", "team_submission")
                        .append("grade", 0);
                submissionsCollection.insertOne(blankSubmission);
            } else {
                List<String> teams_that_graded = team_submission.getList("reviews", String.class);
                if (teams_that_graded == null)
                    throw new WebApplicationException(Response.status(Response.Status.BAD_REQUEST).entity("Assigned teams not found for: " + team + "for assignment: " + assignmentID).build());
                int total_points = 0;
                int count_of_reviews_submitted = teams_that_graded.size();
                for (String review : teams_that_graded) {
                    Document team_review = submissionsCollection.find(and(
                            eq("course_id", courseID),
                            eq("assignment_id", assignmentID),
                            eq("reviewed_by", review),
                            //eq("reviewed_team", teamName),
                            eq("type", "peer_review_submission"))).first();
                    if (team_review == null) {
                        count_of_reviews_submitted--;
                    } else {
                        if (team_review.get("grade", Integer.class) == null) {
                            throw new WebApplicationException(Response.status(Response.Status.BAD_REQUEST).entity("team: " + review + "'s review has no points.").build());
                        } else {
                            total_points += team_review.get("grade", Integer.class);

                        }
                    }
                }
                DecimalFormat tenth = new DecimalFormat("0.##");
                double final_grade = Double.parseDouble(tenth.format((((double) total_points / count_of_reviews_submitted) / points) * 100));//round

                submissionsCollection.findOneAndUpdate(team_submission, set("grade", final_grade));
                assignmentCollection.findOneAndUpdate(and(eq("course_id", courseID), eq("assignment_id", assignmentID)), set("grade_finalized", true));
            }
        }
    }

    public Document getGradeForTeam(String courseID, int assignmentID, String teamName) {
        Document result = submissionsCollection.find(and(
                eq("course_id", courseID),
                eq("assignment_id", assignmentID),
                eq("team_name", teamName),
                eq("type", "team_submission"))).first();
        if (result == null)
            throw new WebApplicationException(Response.status(Response.Status.BAD_REQUEST).entity("Team: " + teamName + " was not found for assignment").build());
        if (result.getInteger("grade") == null) return new Document("grade", -1);
        else return new Document("grade", result.getInteger("grade"));
    }


    /**
     * This method returns a JSON object(Document), that contains all relevant information regarding
     * the grades a team ahs received and the average of the grades received, as well as the average grade
     * each team has given to other teams. The JSON document it returns also has a boolean value associated
     * with the grade stating false if the value is not an outlier, and true if the value is an outlier
     *
     * Notes:
     * This method has the assumed functionality that every team in the course has been assigned/performed
     * a peer review, as determining if a team was excluded from the assignment/peer-review process was far
     * too much work given the data available from querying from the respectove databases.
     * */
    public Document getMatrixOfGrades(String courseID, int assignmentID){

        //get all teams that were assigned this assignment in the course(will be the 'iterable')
        Document assignment = assignmentCollection.find(and(eq("course_id", courseID), eq("assignment_id", assignmentID))).first();
        if (assignment == null)
            throw new WebApplicationException(Response.status(Response.Status.BAD_REQUEST).entity("Assignment not found.").build());
        //grab all teams assigned this
        List<String> allTeams = assignment.getList("all_teams", String.class);


        //we must sort them based on alphabetical order
        Collections.sort(allTeams);

        //then find every team that graded them(should be a base function call already made inside this file)
        Document matrixOfGrades = new Document();

        for(String teamForThisAssignment : allTeams) {

            //get document for each team for this assignment
            Document team_submission = submissionsCollection.find(and(
                    eq("course_id", courseID),
                    eq("assignment_id", assignmentID),
                    eq("team_name", teamForThisAssignment),
                    eq("type", "team_submission"))).first();

            //grade the teams that 'reviewed'/graded this team
            List<String> teams_that_graded = team_submission.getList("reviews", String.class);
            int sizeOfTeamsThatGraded = teams_that_graded.size();

            //sort teams_that_graded
            Collections.sort(teams_that_graded);

            //create doc for holding each team that graded and if its an outlier or not
            Document gradesToOutliers = new Document();

            //integer values to keep track of average of scores and number of reviews each team made
            int totalSum = 0, counter=0;

            for (String teamThatGraded : teams_that_graded) {
                //for each team in the reviews, we need to find the team that reviewed the current team
                Document team_review = submissionsCollection.find(and(
                        eq("course_id", courseID),
                        eq("assignment_id", assignmentID),
                        eq("reviewed_by", teamThatGraded),
                        eq("reviewed_team", teamForThisAssignment),
                        eq("type", "peer_review_submission"))).first();

                //if the grade is an outlier(boolean = true, else boolean = false)
                int respectiveGrade = team_review.get("grade", Integer.class);
                //sum the total and increase counter
                totalSum += respectiveGrade;
                counter++;
                System.out.println("counter: " + counter);
                double average = (double)totalSum / (double)counter;



                if (isOutlier(courseID, assignmentID, respectiveGrade)) {
                    //fill in the values if they're an outlier
                    gradesToOutliers.append(teamThatGraded, new Document(String.valueOf(respectiveGrade), true));
                    if(counter == sizeOfTeamsThatGraded) {
                        gradesToOutliers.append("Average Grade Received", new Document(String.valueOf(average), isOutlier(courseID, assignmentID, average)));
                        System.out.println("value is an: " + isOutlier(courseID, assignmentID, average));
                    }


                }
                else {
                    gradesToOutliers.append(teamThatGraded, new Document(String.valueOf(respectiveGrade), false));
                    if(counter == sizeOfTeamsThatGraded) {
                        gradesToOutliers.append("Average Grade Received", new Document(String.valueOf(average), isOutlier(courseID, assignmentID, average)));
                        System.out.println("value is an: " + isOutlier(courseID, assignmentID, average));
                    }

                }


            }
            matrixOfGrades.append(teamForThisAssignment, gradesToOutliers);

        }

        //hashmaps to keep track of grades each team has given/they're review count
        HashMap<String, Integer> teamsToGradesGiven = new HashMap<>();
        HashMap<String, Integer> teamsToCountOfReviews = new HashMap<>();

        //create values for each team in a hashmap for their grades
        for(String teams : matrixOfGrades.keySet()){
            teamsToGradesGiven.put(teams, 0);
            teamsToCountOfReviews.put(teams, 0);
        }

        //I know three loops is disgusting, but the way the document we created above is formatted,
        //the grade is three levels deep, so we need to go to that depth to get the grade

        //grab the keys of the doc(all the grades in document we just created)
        for(String keysInMatrixDoc : matrixOfGrades.keySet()){

            //this will retrieve the list of teams who have given grades
            Object valuesOfKeys = matrixOfGrades.get(keysInMatrixDoc);

            //try casting the object to a document
            Document valuesOfEachKey = (Document) valuesOfKeys;

            for(String subKeySet : valuesOfEachKey.keySet()){

                //to get the grade document we need to go one step further
                Document gradesAndBoolean = (Document) valuesOfEachKey.get(subKeySet);

                for(String grade : gradesAndBoolean.keySet()){
                    //if current subKeyString equals the a hashmap key, sum current value and counter)
                    if(teamsToGradesGiven.containsKey(subKeySet)){
                        teamsToGradesGiven.put(subKeySet, teamsToGradesGiven.get(subKeySet) + Integer.parseInt(grade));
                        teamsToCountOfReviews.put(subKeySet, teamsToCountOfReviews.get(subKeySet) + 1);
                    }

                }

            }

        }


        //document to find/grad the grades, will append this to each section of the documentToAppendGrades
        Document gradesHolder = new Document();

        //create document to then append to the matric doc(for grades given averages)
        for(String key : teamsToGradesGiven.keySet()){
            //first calculate average
            double average = (double)teamsToGradesGiven.get(key) / (double)teamsToCountOfReviews.get(key);

            if(isOutlier(courseID, assignmentID, average)){
                gradesHolder.append(key, new Document(String.valueOf(average), true));
            }
            else{
                gradesHolder.append(key, new Document(String.valueOf(average), false));
            }
        }

        matrixOfGrades.append("Average Grade Given", gradesHolder);

        return matrixOfGrades;
    }


    /**
     * abstraction method that calls calculate IQR, and uses the values calculated from there
     * to return a boolean value of whether a number is an outlier or not, based on the current
     * grades received for this assignment(this function takes a double to compare)
     * */
    private boolean isOutlier(String courseID, int assignmentID, double numberToCompare){
        HashMap<String, Integer> calculatedQuantities = new HashMap<String, Integer>();
        calculatedQuantities = calculateIQR(courseID, assignmentID);
        int Q1 = calculatedQuantities.get("Q1");
        int Q3 = calculatedQuantities.get("Q3");
        int IQR = calculatedQuantities.get("IQR");

        //if value is an outlier
        if( (numberToCompare < (Q1 - (1.5 * IQR)))  || (numberToCompare > (Q3 + (1.5 * IQR)) )){
            return true;
        }
        //if its not an outlier
        else{
            return false;
        }
    }

    /**
     * abstraction method that calls calculate IQR, and uses the values calculated from there
     * to return a boolean value of whether a number is an outlier or not, based on the current
     * grades received for this assignment(this function takes an int to compare)
     * */
    private boolean isOutlier(String courseID, int assignmentID, int numberToCompare){
        HashMap<String, Integer> calculatedQuantities = new HashMap<String, Integer>();
        calculatedQuantities = calculateIQR(courseID, assignmentID);
        int Q1 = calculatedQuantities.get("Q1");
        int Q3 = calculatedQuantities.get("Q3");
        int IQR = calculatedQuantities.get("IQR");

        //if value is an outlier
        if( (numberToCompare < (Q1 - (1.5 * IQR)))  || (numberToCompare > (Q3 + (1.5 * IQR)) )){
            return true;
        }
        //if its not an outlier
        else{
            return false;
        }
    }

    /**
     * This function is used to calculate the IQR, returning a hashmap of values
     * that consist of the q1, q3, and IQR values to allow for computation and
     * outlier detection.
     * */
    private HashMap<String, Integer> calculateIQR(String courseID, int assignmentID){
        //must make a query to the DB to grab all of the grades
        Document assignment = assignmentCollection.find(and(eq("course_id", courseID), eq("assignment_id", assignmentID))).first();
        if (assignment == null)
            throw new WebApplicationException(Response.status(Response.Status.BAD_REQUEST).entity("Assignment not found.").build());
        List<String> allTeams = assignment.getList("all_teams", String.class);
        //for every team in the course, grab the points, and add them to an integer array
        List<Integer> gradesForAssignment= new ArrayList<Integer>();

        for(String everyTeamAssigned : allTeams){

            //get document for each team for this assignment
            Document team_submission = submissionsCollection.find(and(
                    eq("course_id", courseID),
                    eq("assignment_id", assignmentID),
                    eq("team_name", everyTeamAssigned),
                    eq("type", "team_submission"))).first();

            //grade the teams that 'reviewed'/graded this team
            List<String> teams_that_graded = team_submission.getList("reviews", String.class);

            //sort teams_that_graded
            Collections.sort(teams_that_graded);

            for(String teamsThatReviewedThisTeam : teams_that_graded) {

                //for each team in the reviews, we need to find the team that reviewed the current team
                Document team_review = submissionsCollection.find(and(
                        eq("course_id", courseID),
                        eq("assignment_id", assignmentID),
                        eq("reviewed_by", teamsThatReviewedThisTeam),
                        eq("reviewed_team", everyTeamAssigned),
                        eq("type", "peer_review_submission"))).first();

                if (team_review == null) {
                    throw new WebApplicationException(Response.status(Response.Status.BAD_REQUEST).entity("Error retrieving Team review.").build());
                }
                if (team_review.get("grade", Integer.class) == null) {
                    throw new WebApplicationException(Response.status(Response.Status.BAD_REQUEST).entity("Error retreiving grade for team").build());
                }
                else {

                    int respectiveGrade = team_review.get("grade", Integer.class);
                    gradesForAssignment.add(respectiveGrade);
                }

            }


        }

        int IQR = 0;
        //after all of the team grades are obtained,

        //we must sort them
        Collections.sort(gradesForAssignment);

        HashMap<Integer, List<Integer>> subsets = getSubsetOfArray(gradesForAssignment);

        //get subsets from hashmap
        List<Integer> q1Subset = subsets.get(1);
        List<Integer> q3Subset = subsets.get(2);

        //get medians from subsets
        int q1Median = findMedian(q1Subset);
        int q3Median = findMedian(q3Subset);

        //get IQR from subtracting both values
        IQR = q3Median - q1Median;
        HashMap<String, Integer> returnValues = new HashMap<String, Integer>();
        returnValues.put("Q1", q1Median);
        returnValues.put("Q3", q3Median);
        returnValues.put("IQR", IQR);

        return returnValues;
    }

    /**
     * This fucntion returns a subset of the array to then be able to calculate the
     * median for each 'Q'
     * */
    private HashMap<Integer, List<Integer>> getSubsetOfArray(List<Integer> input){
        List<Integer> firstSubSet = new ArrayList<Integer>();
        List<Integer> secondSubSet = new ArrayList<Integer>();

        HashMap< Integer, List<Integer> > subsetOfArrays = new HashMap<>();
        //if true this is odd
        if((input.size() & 1) == 1){
            firstSubSet = input.subList(0, input.size() / 2);
            secondSubSet = input.subList(input.size() / 2 + 1, input.size());
        }
        else {
            firstSubSet = input.subList(0, input.size() / 2);
            secondSubSet = input.subList(input.size() / 2, input.size());
        }
        subsetOfArrays.put(1, firstSubSet);
        subsetOfArrays.put(2, secondSubSet);

        return subsetOfArrays;

    }

    /**
     * This function returns the median of any dataset that is larger than 2, also
     * it assumes that the data is already sorted when passed in
     * */
    private int findMedian(List<Integer> dataSet){
        //this fucntion won't accept an array of length less than 2,
        if(dataSet.size() < 2 || dataSet == null )
            return -1;
        //& 1 is a bitwise operator that is much faster than modulo and determines whether a number is odd or even
        if((dataSet.size() & 1) == 1)
            //use int division return median
            return dataSet.get(dataSet.size() / 2);
        else
            //must use formula (((dataSet.length/2) + (dataSet.length/2 -1)) / 2) to obtain the proper index of even length dataset )
            return (dataSet.get(dataSet.size() / 2) + dataSet.get(dataSet.size() / 2 - 1)) / 2;

    }



}
