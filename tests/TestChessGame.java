package tests;

import game.ChessGame;
import game.ChessGame.Color;
import game.ChessGame.MoveResult;

public class TestChessGame {
    public static void run() {
        System.out.println("-> Running TestChessGame...");
        ChessGame game = new ChessGame();

        // 1. Initial State
        assert game.getCurrentTurn() == Color.WHITE : "Initial turn must be WHITE";
        assert !game.isGameOver() : "Game should not be over initially";

        // 2. White Pawn Move e2 -> e4 (valid)
        MoveResult r1 = game.makeMove("e2", "e4", Color.WHITE);
        assert r1.isSuccess() : "e2 to e4 should be valid for White";
        assert game.getCurrentTurn() == Color.BLACK : "Turn should switch to BLACK";

        // 3. Invalid turn attempt (White trying to play when it's Black's turn)
        MoveResult r2 = game.makeMove("d2", "d4", Color.WHITE);
        assert !r2.isSuccess() : "White cannot move on Black's turn";

        // 4. Black Knight Move g8 -> f6 (valid)
        MoveResult r3 = game.makeMove("g8", "f6", Color.BLACK);
        assert r3.isSuccess() : "g8 to f6 should be valid for Black Knight";
        assert game.getCurrentTurn() == Color.WHITE : "Turn should switch back to WHITE";

        // 5. White Bishop Move f1 -> c4 (valid)
        MoveResult r4 = game.makeMove("f1", "c4", Color.WHITE);
        assert r4.isSuccess() : "f1 to c4 should be valid for White Bishop";

        // 6. Invalid Move (Knight jumping to invalid position)
        MoveResult r5 = game.makeMove("f6", "f5", Color.BLACK);
        assert !r5.isSuccess() : "Knight moving straight should fail";

        // 7. Resign test
        game.resign(Color.BLACK);
        assert game.isGameOver() : "Game should be over after resignation";
        assert game.getWinner() == Color.WHITE : "White should win after Black resigns";

        System.out.println("   [✓] TestChessGame PASSED");
    }
}
