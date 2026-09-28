package tests;

import game.ChessGame;
import game.ChessGame.Color;
import game.ChessGame.MoveResult;

public class TestChessGame {
    public static void run() {
        System.out.println("-> Running TestChessGame...");
        ChessGame game = new ChessGame();

        assert game.getCurrentTurn() == Color.WHITE : "Initial turn must be WHITE";
        assert !game.isGameOver() : "Game should not be over initially";

        MoveResult r0 = game.makeMove("a1", "a1", Color.WHITE);
        assert !r0.isSuccess() : "Degenerate self-move must fail";

        MoveResult r1 = game.makeMove("e2", "e4", Color.WHITE);
        assert r1.isSuccess() : "e2 to e4 should be valid for White";
        assert game.getCurrentTurn() == Color.BLACK : "Turn should switch to BLACK";

        MoveResult r2 = game.makeMove("d2", "d4", Color.WHITE);
        assert !r2.isSuccess() : "White cannot move on Black's turn";

        MoveResult r3 = game.makeMove("g8", "f6", Color.BLACK);
        assert r3.isSuccess() : "g8 to f6 should be valid for Black Knight";
        assert game.getCurrentTurn() == Color.WHITE : "Turn should switch back to WHITE";

        MoveResult r4 = game.makeMove("f1", "c4", Color.WHITE);
        assert r4.isSuccess() : "f1 to c4 should be valid for White Bishop";

        MoveResult r5 = game.makeMove("f6", "f5", Color.BLACK);
        assert !r5.isSuccess() : "Knight moving straight should fail";

        ChessGame promoGame = new ChessGame();
        assert promoGame.makeMove("h2", "h4", Color.WHITE).isSuccess();
        assert promoGame.makeMove("a7", "a6", Color.BLACK).isSuccess();
        assert promoGame.makeMove("h4", "h5", Color.WHITE).isSuccess();
        assert promoGame.makeMove("a6", "a5", Color.BLACK).isSuccess();
        assert promoGame.makeMove("h5", "h6", Color.WHITE).isSuccess();
        assert promoGame.makeMove("a5", "a4", Color.BLACK).isSuccess();
        assert promoGame.makeMove("h6", "g7", Color.WHITE).isSuccess();
        assert promoGame.makeMove("b7", "b6", Color.BLACK).isSuccess();
        assert promoGame.makeMove("g7", "h8", Color.WHITE).isSuccess() : "Pawn promotion capture should succeed";
        String promoBoard = promoGame.renderBoard();
        assert promoBoard.contains(" Q ") : "Promotion should create a white queen";

        ChessGame mateGame = new ChessGame();
        assert mateGame.makeMove("f2", "f3", Color.WHITE).isSuccess();
        assert mateGame.makeMove("e7", "e5", Color.BLACK).isSuccess();
        assert mateGame.makeMove("g2", "g4", Color.WHITE).isSuccess();
        MoveResult mateMove = mateGame.makeMove("d8", "h4", Color.BLACK);
        assert mateMove.isSuccess() : "Checkmating move should succeed";
        assert mateGame.isGameOver() : "Game should end on checkmate";
        assert mateGame.getWinner() == Color.BLACK : "Black should win in fool's mate";

        game.resign(Color.BLACK);
        assert game.isGameOver() : "Game should be over after resignation";
        assert game.getWinner() == Color.WHITE : "White should win after Black resigns";

        System.out.println("   [✓] TestChessGame PASSED");
    }
}
